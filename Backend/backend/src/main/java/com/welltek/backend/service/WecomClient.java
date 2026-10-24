package com.welltek.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

// 【企业微信接口】两条渠道：
// 1. “ID助手”自建应用：用 corp-id + secret 换 access_token，再按企业微信账号推送给个人（message/send）；
// 2. 群机器人：往 Webhook 地址发消息，消息出现在机器人所在的群里。
// 只负责“把一段 markdown 发出去”，发什么、发给谁由 PushService 决定。
@Component
public class WecomClient {

    private static final Logger log = LoggerFactory.getLogger(WecomClient.class);

    @Value("${app.wecom.corp-id:}")
    private String corpId;

    @Value("${app.wecom.agent-id:}")
    private String agentId;

    @Value("${app.wecom.secret:}")
    private String secret;

    @Value("${app.wecom.api-base:https://qyapi.weixin.qq.com}")
    private String apiBase;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    /** 发送结果：ok + 给人看的说明 */
    public record Result(boolean ok, String message) {
    }

    /** “ID助手”三个参数都填了才算配置好 */
    public boolean appConfigured() {
        return notBlank(corpId) && notBlank(agentId) && notBlank(secret);
    }

    /** 群机器人 Webhook 地址必须是这个前缀（防止把消息发到别的网站去） */
    public String webhookPrefix() {
        return apiBase + "/cgi-bin/webhook/send?key=";
    }

    /** 用“ID助手”应用发 markdown 给一批人（企业微信账号） */
    public Result sendAppMarkdown(Collection<String> userIds, String markdown) {
        if (!appConfigured()) {
            return new Result(false, "ID助手应用没有配置（服务器配置文件里的 app.wecom.* 为空）");
        }
        if (userIds.isEmpty()) {
            return new Result(false, "没有可推送的人（相关人员都没有登记企业微信账号）");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", String.join("|", userIds));
        body.put("msgtype", "markdown");
        body.put("agentid", Integer.parseInt(agentId.trim()));
        body.put("markdown", Map.of("content", markdown));
        try {
            JsonNode res = postJson(apiBase + "/cgi-bin/message/send?access_token=" + accessToken(false), body);
            int code = res.path("errcode").asInt(-1);
            if (code == 40014 || code == 42001 || code == 40001) { // token 失效：刷新后重试一次
                res = postJson(apiBase + "/cgi-bin/message/send?access_token=" + accessToken(true), body);
                code = res.path("errcode").asInt(-1);
            }
            if (code != 0) {
                return new Result(false, explain(code, res.path("errmsg").asText()));
            }
            String invalid = res.path("invaliduser").asText("");
            if (!invalid.isBlank()) {
                return new Result(true, "已推送，但这些企业微信账号无效（请检查用户管理里登记的账号）：" + invalid.replace("|", "、"));
            }
            return new Result(true, "已推送给 " + userIds.size() + " 人");
        } catch (WecomException e) {
            return new Result(false, e.getMessage());
        } catch (Exception e) {
            log.warn("企业微信应用消息发送失败", e);
            return new Result(false, "连不上企业微信（" + apiBase + "），请确认服务器能访问外网：" + e.getMessage());
        }
    }

    /** 用群机器人发 markdown 到群里 */
    public Result sendWebhookMarkdown(String webhookUrl, String markdown) {
        if (!notBlank(webhookUrl)) {
            return new Result(false, "没有设置群机器人 Webhook 地址");
        }
        try {
            JsonNode res = postJson(webhookUrl, Map.of("msgtype", "markdown", "markdown", Map.of("content", markdown)));
            int code = res.path("errcode").asInt(-1);
            return code == 0 ? new Result(true, "已推送到群") : new Result(false, explain(code, res.path("errmsg").asText()));
        } catch (Exception e) {
            log.warn("企业微信群机器人发送失败", e);
            return new Result(false, "连不上企业微信（群机器人），请确认服务器能访问外网：" + e.getMessage());
        }
    }

    // ---------------- 内部方法 ----------------

    private synchronized String accessToken(boolean forceRefresh) throws Exception {
        if (!forceRefresh && token != null && Instant.now().isBefore(tokenExpiresAt)) {
            return token;
        }
        String url = apiBase + "/cgi-bin/gettoken?corpid=" + enc(corpId.trim()) + "&corpsecret=" + enc(secret.trim());
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode res = objectMapper.readTree(resp.body());
        int code = res.path("errcode").asInt(-1);
        if (code != 0) {
            throw new WecomException(explain(code, res.path("errmsg").asText()));
        }
        token = res.path("access_token").asText();
        // 企业微信给的有效期一般是 7200 秒，提前 5 分钟刷新
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, res.path("expires_in").asLong(7200) - 300));
        return token;
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return objectMapper.readTree(resp.body());
    }

    /** 常见错误码翻译成看得懂的话 */
    private static String explain(int code, String errmsg) {
        String hint = switch (code) {
            case 40013 -> "企业 ID（app.wecom.corp-id）不对";
            case 40001, 42001, 40014 -> "应用密钥（app.wecom.secret）不对或已失效";
            case 40056, 301002 -> "应用 ID（app.wecom.agent-id）不对";
            case 60020 -> "服务器的公网 IP 不在“ID助手”的企业可信 IP 里，请企业微信管理员在应用后台添加";
            case 81013 -> "接收人的企业微信账号全部无效，请检查用户管理里登记的账号";
            case 93000 -> "群机器人 Webhook 地址无效（机器人可能已被删除）";
            case 45009, 45033 -> "发送太频繁，被企业微信限流了，请稍后再试";
            case 40058 -> "消息内容不合规范（可能太长）";
            default -> "企业微信返回错误";
        };
        return hint + "（错误码 " + code + "：" + errmsg + "）";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static class WecomException extends RuntimeException {
        WecomException(String message) {
            super(message);
        }
    }
}
