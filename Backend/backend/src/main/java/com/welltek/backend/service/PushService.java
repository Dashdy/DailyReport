package com.welltek.backend.service;

import com.welltek.backend.config.BizException;
import com.welltek.backend.dto.Requests;
import com.welltek.backend.entity.*;
import com.welltek.backend.mapper.*;
import com.welltek.backend.security.LoginUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

// 【企业微信推送】
// 推什么：
//   - 工序战报：一道工序当天的六项目标 / 实际 / 是否达标 + 四功能小组；
//   - 全厂汇总：全厂达标率、未达标的工序、今日未填报的工序（合成一条）。
// 推给谁：
//   - 渠道一“ID助手”应用（个人）：工序战报 → 这道工序的负责人 + 接收人名单；全厂汇总 → 接收人名单；
//   - 渠道二群机器人（设置了 Webhook 才发）：工序战报和全厂汇总都发到群里。
// 什么时候推：
//   - 负责人 / 管理员在工序详情页点“推送到企业微信”；
//   - 每天到了“自动推送时间”，推送当天已填报的各工序战报 + 全厂汇总；
//     手动推过、之后数据没变的工序跳过，推过之后又改了数据的会再推一次最新的。
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    public static final String KEY_ENABLED = "PUSH_ENABLED";
    public static final String KEY_TIME = "PUSH_TIME";
    public static final String KEY_TITLE = "PUSH_TITLE";
    public static final String KEY_RECEIVERS = "PUSH_RECEIVERS";
    public static final String KEY_WEBHOOK = "GROUP_WEBHOOK";
    public static final String KEY_LAST_AUTO = "PUSH_LAST_AUTO";
    private static final String DEFAULT_TIME = "20:30";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd");
    /** 群机器人每分钟最多 20 条，批量推送时每条之间隔开 */
    private static final long WEBHOOK_GAP_MS = 3200;
    /** 企业微信 markdown 内容上限 4096 字节 */
    private static final int MAX_BYTES = 4000;

    private static final Map<Metric, String> ICONS = Map.of(
            Metric.SAFETY, "🛡", Metric.QUALITY, "🎯", Metric.DELIVERY, "🚚",
            Metric.SCRAP, "📉", Metric.COST, "💰", Metric.EQUIPMENT, "🔧");

    @Autowired
    private WecomClient wecom;
    @Autowired
    private SettingMapper settingMapper;
    @Autowired
    private PushLogMapper pushLogMapper;
    @Autowired
    private ProcessMapper processMapper;
    @Autowired
    private TargetMapper targetMapper;
    @Autowired
    private ItemMapper itemMapper;
    @Autowired
    private RecordMapper recordMapper;
    @Autowired
    private BoardMapper boardMapper;
    @Autowired
    private NoteMapper noteMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ProcessService processService;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private AuditService auditService;

    @Value("${app.public-url:}")
    private String publicUrl;

    /** 批量推送放到后台线程跑，不卡住页面请求；单线程保证不会两批同时推 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wecom-push");
        t.setDaemon(true);
        return t;
    });

    // ================= 设置 =================

    public Map<String, Object> settings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appConfigured", wecom.appConfigured());
        m.put("enabled", "true".equals(settingMapper.selectValue(KEY_ENABLED)));
        m.put("time", pushTime().format(HH_MM));
        m.put("title", nvl(settingMapper.selectValue(KEY_TITLE)));
        m.put("receivers", receiverIds());
        String webhook = settingMapper.selectValue(KEY_WEBHOOK);
        m.put("webhookConfigured", webhook != null && !webhook.isBlank());
        m.put("webhookMasked", maskWebhook(webhook));
        m.put("webhookPrefix", wecom.webhookPrefix());
        m.put("lastAuto", settingMapper.selectValue(KEY_LAST_AUTO));
        m.put("logs", pushLogMapper.selectRecent(30));
        return m;
    }

    public void saveSettings(LoginUser op, Requests.SavePush req) {
        Map<String, Object> before = describeSettings();
        boolean enabled = Boolean.TRUE.equals(req.enabled());
        String time;
        try {
            time = LocalTime.parse(req.time() == null ? DEFAULT_TIME : req.time().trim(), HH_MM).format(HH_MM);
        } catch (DateTimeParseException e) {
            throw new BizException("自动推送时间格式不对，请填写 HH:mm，例如 20:30");
        }
        Set<Integer> validIds = userMapper.selectAll().stream().map(User::id).collect(Collectors.toSet());
        List<Integer> receivers = req.receivers() == null ? List.of() : req.receivers().stream().filter(validIds::contains).distinct().toList();

        settingMapper.upsert(KEY_ENABLED, String.valueOf(enabled), op.username());
        settingMapper.upsert(KEY_TIME, time, op.username());
        settingMapper.upsert(KEY_TITLE, trimToNull(req.title()), op.username());
        settingMapper.upsert(KEY_RECEIVERS, receivers.stream().map(String::valueOf).collect(Collectors.joining(",")), op.username());

        // Webhook：传回来的还是打码后的样子，说明没改；空字符串表示清除
        String webhook = req.webhook() == null ? null : req.webhook().trim();
        if (webhook != null && !webhook.contains("****")) {
            if (!webhook.isEmpty() && !webhook.startsWith(wecom.webhookPrefix())) {
                throw new BizException("Webhook 地址不对，应该以 " + wecom.webhookPrefix() + " 开头（在群机器人的资料页复制）");
            }
            settingMapper.upsert(KEY_WEBHOOK, webhook.isEmpty() ? null : webhook, op.username());
        }
        auditService.log(op, AuditService.MODULE_PUSH, "修改", null, "企业微信推送设置", before, describeSettings());
    }

    // ================= 预览 / 手动推送 / 测试 =================

    /** 工序战报预览：内容 + 会发给谁 */
    public Map<String, Object> preview(Integer processId, LocalDate date) {
        ProcessStep p = processService.requireProcess(processId);
        Recipients r = processRecipients(processId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", buildProcessReport(p, date));
        m.put("receivers", r.names());
        m.put("missing", r.missing());
        m.put("appConfigured", wecom.appConfigured());
        m.put("webhookConfigured", webhook() != null);
        m.put("history", pushLogMapper.selectByProcess(processId, date));
        return m;
    }

    /** 负责人 / 管理员手动推送某工序某天的战报 */
    public String pushProcessManually(LoginUser op, Integer processId, LocalDate date) {
        ProcessStep p = processService.requireProcess(processId);
        if (!p.active()) {
            throw new BizException("“" + p.name() + "”已停用，不能推送");
        }
        if (!op.canEdit(processId)) {
            throw new BizException("你不是“" + p.name() + "”的负责人，不能推送这道工序的战报");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new BizException("不能推送未来日期的战报");
        }
        String result = pushProcess(p, date, "MANUAL", op.username(), false);
        auditService.log(op, AuditService.MODULE_PUSH, "推送", p.name(), date + " 工序战报", null, result);
        return result;
    }

    /** 测试消息：ID助手发给自己（要先登记自己的企业微信账号），群机器人发到群里 */
    public String sendTest(LoginUser op) {
        String content = "**每日战报系统 · 测试消息**\n> 发送人：" + op.username() + "\n> 时间：" +
                LocalDateTime.now().withNano(0).toString().replace('T', ' ') + "\n收到这条消息说明企业微信推送配置正确。";
        List<String> parts = new ArrayList<>();
        boolean anyOk = false;
        if (wecom.appConfigured()) {
            User me = userMapper.selectById(op.id());
            if (me == null || me.wecomUserId() == null || me.wecomUserId().isBlank()) {
                parts.add("ID助手：你自己还没登记企业微信账号（用户管理里填），没法发给你");
            } else {
                WecomClient.Result r = wecom.sendAppMarkdown(List.of(me.wecomUserId()), content);
                anyOk |= r.ok();
                parts.add("ID助手：" + r.message());
            }
        } else {
            parts.add("ID助手：服务器配置文件里还没填 app.wecom.* 三个参数");
        }
        String hook = webhook();
        if (hook != null) {
            WecomClient.Result r = wecom.sendWebhookMarkdown(hook, content);
            anyOk |= r.ok();
            parts.add("群机器人：" + r.message());
        } else {
            parts.add("群机器人：还没设置 Webhook 地址");
        }
        String msg = String.join("；", parts);
        pushLogMapper.insert(null, LocalDate.now(), "TEST", "TEST", op.username(), null, null, anyOk, msg);
        auditService.log(op, AuditService.MODULE_PUSH, "测试", null, "企业微信测试消息", null, msg);
        return msg;
    }

    /** 管理员点“立即执行一次自动推送”：后台跑，马上返回 */
    public void runAutoPushNow(LoginUser op) {
        auditService.log(op, AuditService.MODULE_PUSH, "手动执行", null, "自动推送（今天）", null, "已开始");
        worker.submit(() -> autoPush(LocalDate.now(), op.username()));
    }

    // ================= 定时自动推送 =================

    /** 每分钟由 PushScheduler 调用：到了设置的时间（之后 1 小时内都算，防止后端刚好在那一分钟重启错过），当天只推一次 */
    public void autoTick() {
        if (!"true".equals(settingMapper.selectValue(KEY_ENABLED))) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime due = now.toLocalDate().atTime(pushTime());
        if (now.isBefore(due) || now.isAfter(due.plusHours(1))) {
            return;
        }
        String today = now.toLocalDate().toString();
        if (today.equals(settingMapper.selectValue(KEY_LAST_AUTO))) {
            return;
        }
        settingMapper.upsert(KEY_LAST_AUTO, today, "系统定时");
        worker.submit(() -> autoPush(now.toLocalDate(), "系统定时"));
    }

    /** 推送当天各工序战报（已填报、且跟上次推送的内容不同的）+ 全厂汇总 */
    void autoPush(LocalDate date, String operator) {
        try {
            int sent = 0, skipped = 0, failed = 0;
            // 跟首页同一个口径：有效子项里至少填了一个数才算已填报
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) dashboardService.summary(date).get("rows");
            Set<Integer> reported = rows.stream().filter(row -> (boolean) row.get("reported"))
                    .map(row -> (Integer) row.get("processId")).collect(Collectors.toSet());
            for (ProcessStep p : processMapper.selectAll(false)) {
                if (!reported.contains(p.id())) {
                    continue; // 没填报的工序不单独发，统一合并进全厂汇总里的“今日未填报”
                }
                String content = buildProcessReport(p, date);
                if (hash(content).equals(pushLogMapper.lastSuccessHash(p.id(), date))) {
                    skipped++; // 已经推过，而且之后数据没变
                    continue;
                }
                String r = pushProcess(p, date, "AUTO", operator, true);
                if (r.startsWith("推送失败")) failed++;
                else sent++;
            }
            String summary = buildSummary(date);
            pushSummary(date, summary, operator);
            log.info("企业微信自动推送完成（{}）：工序战报推送 {} 条，跳过 {} 条（已推送且无变化），失败 {} 条", date, sent, skipped, failed);
        } catch (Exception e) {
            log.error("企业微信自动推送出错", e);
            pushLogMapper.insert(null, date, "SUMMARY", "AUTO", operator, null, null, false, "自动推送出错：" + e.getMessage());
        }
    }

    // ================= 发送 =================

    /** 推一道工序的战报，返回给人看的结果说明；throttle=true 时群机器人每条之间隔开 */
    private String pushProcess(ProcessStep p, LocalDate date, String trigger, String operator, boolean throttle) {
        String content = buildProcessReport(p, date);
        Recipients r = processRecipients(p.id());
        List<String> channels = new ArrayList<>();
        List<String> results = new ArrayList<>();
        boolean anyOk = false, anyChannel = false;

        if (wecom.appConfigured()) {
            anyChannel = true;
            WecomClient.Result res = wecom.sendAppMarkdown(r.userIds(), content);
            anyOk |= res.ok();
            channels.add("ID助手 " + r.userIds().size() + " 人");
            results.add("ID助手：" + res.message());
        }
        String hook = webhook();
        if (hook != null) {
            anyChannel = true;
            if (throttle) sleep(WEBHOOK_GAP_MS);
            WecomClient.Result res = wecom.sendWebhookMarkdown(hook, content);
            anyOk |= res.ok();
            channels.add("群机器人");
            results.add("群机器人：" + res.message());
        }
        if (!anyChannel) {
            results.add("ID助手和群机器人都还没配置，请联系管理员在系统设置里配置");
        }
        if (!r.missing().isEmpty()) {
            results.add("没登记企业微信账号、收不到的人：" + String.join("、", r.missing()));
        }
        String msg = (anyOk ? "推送成功：" : "推送失败：") + String.join("；", results);
        pushLogMapper.insert(p.id(), date, "PROCESS", trigger, operator, String.join(" / ", channels), hash(content), anyOk, msg);
        return msg;
    }

    private void pushSummary(LocalDate date, String content, String operator) {
        List<String> results = new ArrayList<>();
        List<String> channels = new ArrayList<>();
        boolean anyOk = false;
        Recipients r = receivers();
        if (wecom.appConfigured() && !r.userIds().isEmpty()) {
            WecomClient.Result res = wecom.sendAppMarkdown(r.userIds(), content);
            anyOk |= res.ok();
            channels.add("ID助手 " + r.userIds().size() + " 人");
            results.add("ID助手：" + res.message());
        }
        String hook = webhook();
        if (hook != null) {
            sleep(WEBHOOK_GAP_MS);
            WecomClient.Result res = wecom.sendWebhookMarkdown(hook, content);
            anyOk |= res.ok();
            channels.add("群机器人");
            results.add("群机器人：" + res.message());
        }
        if (channels.isEmpty()) {
            results.add("没有可推送的渠道（接收人名单为空或都没登记企业微信账号，也没设置群机器人）");
        }
        pushLogMapper.insert(null, date, "SUMMARY", "AUTO", operator, String.join(" / ", channels), hash(content), anyOk,
                (anyOk ? "推送成功：" : "推送失败：") + String.join("；", results));
    }

    // ================= 消息内容 =================

    /** 工序战报，格式参考车间现在手发的模板 */
    public String buildProcessReport(ProcessStep p, LocalDate date) {
        Map<Metric, MetricTarget> targets = targetMapper.selectByProcess(p.id());
        Map<Integer, DailyRecord> records = new HashMap<>();
        recordMapper.selectByProcess(p.id(), date, date).forEach(r -> records.put(r.itemId(), r));
        Map<Integer, Double> values = MetricJudge.values(records.values());
        ProcessBoard board = boardMapper.selectByProcess(p.id());

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(titlePrefix()).append(p.name()).append(" ").append(date.format(MM_DD)).append(" 日战报**\n");
        if (board != null && board.members() != null) {
            String team = board.members().stream()
                    .filter(m -> m.name() != null && !m.name().isBlank())
                    .map(m -> m.name() + (m.role() == null || m.role().isBlank() ? "" : "（" + m.role() + "）"))
                    .collect(Collectors.joining("、"));
            if (!team.isEmpty()) {
                sb.append("四功能小组：").append(team).append("\n");
            }
        }
        sb.append("\n");
        int pass = 0, fail = 0, empty = 0;
        Set<String> fillers = new LinkedHashSet<>();
        // 拆了子项的指标：每个子项单独标 ✓ / ✗，最后的标签是这项整体的结果（所有子项都达标才算达标）
        for (Map.Entry<Metric, List<MetricItem>> e : MetricJudge.group(itemMapper.selectByProcess(p.id())).entrySet()) {
            Metric m = e.getKey();
            List<MetricItem> items = e.getValue();
            MetricTarget t = targets.get(m);
            MetricStatus s = MetricJudge.metric(items, values);
            for (MetricItem item : items) {
                DailyRecord r = records.get(item.id());
                if (r != null && r.updatedBy() != null) fillers.add(r.updatedBy());
            }
            String goal = MetricJudge.targetText(items); // 班组目标由子项自动生成，跟判红绿的规则一致
            String actual;
            if (items.size() == 1) {
                Double v = values.get(items.get(0).id());
                actual = v == null ? "—" : "**" + num(v) + unitSuffix(items.get(0)) + "**"; // 没填的后面已经有“未填”标签了
            } else {
                actual = items.stream().map(i -> {
                    Double v = values.get(i.id());
                    String mark = switch (i.judge(v)) {
                        case PASS -> " <font color=\"info\">✓</font>";
                        case FAIL -> " <font color=\"warning\">✗</font>";
                        default -> "";
                    };
                    return i.name() + " " + (v == null ? "—" : "**" + num(v) + unitSuffix(i) + "**") + mark;
                }).collect(Collectors.joining("、"));
            }
            boolean partly = items.size() > 1 && items.stream().anyMatch(i -> values.get(i.id()) != null);
            String tag = switch (s) {
                case PASS -> { pass++; yield "<font color=\"info\">达标</font>"; }
                case FAIL -> { fail++; yield "<font color=\"warning\">未达标</font>"; }
                case EMPTY -> { empty++; yield "<font color=\"comment\">" + (partly ? "未填完" : "未填") + "</font>"; }
                default -> "<font color=\"comment\">未设目标</font>";
            };
            sb.append("> ").append(ICONS.get(m)).append(" ").append(m.getLabel())
                    .append("｜目标：").append(goal)
                    .append("｜实际：").append(actual).append(" ").append(tag).append("\n");
        }
        sb.append("\n达标 ").append(pass).append(" 项 · 未达标 ").append(fail).append(" 项");
        if (empty > 0) sb.append(" · 未填 ").append(empty).append(" 项");
        sb.append("\n");
        // 今日行动措施 / 今日亮点 / 明日行动计划：没填的写“无”，跟车间手发的模板一样
        DailyNote note = noteMapper.selectOne(p.id(), date);
        if (note != null && !note.isEmpty()) {
            appendNote(sb, "今日行动措施", note.actions());
            appendNote(sb, "今日亮点", note.highlights());
            appendNote(sb, "明日行动计划", note.plans());
        }
        if (!fillers.isEmpty()) {
            sb.append("\n填报人：").append(String.join("、", fillers)).append("\n");
        }
        appendLink(sb, "/processes/" + p.id(), "查看详情");
        return limit(sb.toString());
    }

    /** 全厂汇总：达标率、未达标工序、今日未填报（合成一条） */
    @SuppressWarnings("unchecked")
    public String buildSummary(LocalDate date) {
        Map<String, Object> d = dashboardService.summary(date);
        Map<String, Object> overall = (Map<String, Object>) d.get("overall");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");

        List<String> allPass = new ArrayList<>(), notReported = new ArrayList<>(), failLines = new ArrayList<>(), partLines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = (String) row.get("name");
            int pass = (int) row.get("pass"), fail = (int) row.get("fail"), empty = (int) row.get("empty");
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) row.get("cells");
            if (!(boolean) row.get("reported")) {
                notReported.add(name);
                continue;
            }
            if (pass == Metric.values().length) allPass.add(name);
            if (fail > 0) {
                failLines.add(name + "：" + labelsWith(cells, "FAIL"));
            } else if (empty > 0) {
                partLines.add(name + "（缺 " + labelsWith(cells, "EMPTY") + "）");
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(titlePrefix()).append("全厂每日战报 ").append(date.format(MM_DD)).append("**\n");
        Object rate = overall.get("rate");
        sb.append("全厂达标率：<font color=\"info\">").append(rate == null ? "—" : pct((Double) rate)).append("</font>")
                .append("　已填报 ").append(overall.get("reported")).append(" / ").append(overall.get("processCount")).append(" 道工序\n");
        if (!allPass.isEmpty()) {
            sb.append("\n<font color=\"info\">六项全部达标（").append(allPass.size()).append("）</font>：").append(String.join("、", allPass)).append("\n");
        }
        if (!failLines.isEmpty()) {
            sb.append("\n<font color=\"warning\">未达标（").append(failLines.size()).append("）</font>\n");
            failLines.forEach(l -> sb.append("> ").append(l).append("\n"));
        }
        if (!partLines.isEmpty()) {
            sb.append("\n部分未填（").append(partLines.size()).append("）：").append(String.join("、", partLines)).append("\n");
        }
        if (!notReported.isEmpty()) {
            sb.append("\n<font color=\"warning\">今日未填报（").append(notReported.size()).append("）</font>：").append(String.join("、", notReported)).append("\n");
        }
        sb.append("\n");
        appendLink(sb, "/", "查看全厂汇总");
        return limit(sb.toString());
    }

    // ================= 内部工具 =================

    private record Recipients(List<String> userIds, List<String> names, List<String> missing) {
    }

    /** 工序战报的接收人：负责人 + 接收人名单（只算启用的用户） */
    private Recipients processRecipients(Integer processId) {
        Set<Integer> ids = new LinkedHashSet<>();
        processMapper.selectOwners().getOrDefault(processId, List.of()).forEach(o -> ids.add((Integer) o.get("id")));
        ids.addAll(receiverIds());
        return toRecipients(ids);
    }

    /** 全厂汇总的接收人：接收人名单 */
    private Recipients receivers() {
        return toRecipients(new LinkedHashSet<>(receiverIds()));
    }

    private Recipients toRecipients(Collection<Integer> ids) {
        Map<Integer, User> users = userMapper.selectAll().stream().collect(Collectors.toMap(User::id, u -> u));
        List<String> wecomIds = new ArrayList<>(), names = new ArrayList<>(), missing = new ArrayList<>();
        for (Integer id : ids) {
            User u = users.get(id);
            if (u == null || !u.enabled()) continue;
            if (u.wecomUserId() == null || u.wecomUserId().isBlank()) {
                missing.add(u.username());
            } else if (!wecomIds.contains(u.wecomUserId())) {
                wecomIds.add(u.wecomUserId());
                names.add(u.username());
            }
        }
        return new Recipients(wecomIds, names, missing);
    }

    private List<Integer> receiverIds() {
        String v = settingMapper.selectValue(KEY_RECEIVERS);
        if (v == null || v.isBlank()) return List.of();
        List<Integer> ids = new ArrayList<>();
        for (String s : v.split(",")) {
            try {
                ids.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // 设置值被手工改坏了就忽略这一项
            }
        }
        return ids;
    }

    private LocalTime pushTime() {
        String v = settingMapper.selectValue(KEY_TIME);
        try {
            return LocalTime.parse(v == null || v.isBlank() ? DEFAULT_TIME : v, HH_MM);
        } catch (DateTimeParseException e) {
            return LocalTime.parse(DEFAULT_TIME, HH_MM);
        }
    }

    private String webhook() {
        String v = settingMapper.selectValue(KEY_WEBHOOK);
        return v == null || v.isBlank() ? null : v;
    }

    private String titlePrefix() {
        String t = settingMapper.selectValue(KEY_TITLE);
        return t == null || t.isBlank() ? "" : "【" + t.trim() + "】";
    }

    private Map<String, Object> describeSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("自动推送", "true".equals(settingMapper.selectValue(KEY_ENABLED)) ? "开启" : "关闭");
        m.put("推送时间", pushTime().format(HH_MM));
        m.put("活动名称", nvl(settingMapper.selectValue(KEY_TITLE)));
        Map<Integer, String> names = userMapper.selectAll().stream().collect(Collectors.toMap(User::id, User::username));
        m.put("接收人", receiverIds().stream().map(names::get).filter(Objects::nonNull).collect(Collectors.joining("、")));
        m.put("群机器人", maskWebhook(settingMapper.selectValue(KEY_WEBHOOK)));
        return m;
    }

    private static String maskWebhook(String url) {
        if (url == null || url.isBlank()) return "";
        int i = url.indexOf("key=");
        if (i < 0 || url.length() < i + 8) return "****";
        return url.substring(0, i + 8) + "****";
    }

    private static void appendNote(StringBuilder sb, String title, String text) {
        sb.append("\n**【").append(title).append("】**\n").append(text == null ? "无" : text).append("\n");
    }

    private void appendLink(StringBuilder sb, String path, String text) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            sb.append("[").append(text).append("](").append(publicUrl.replaceAll("/+$", "")).append(path).append(")");
        }
    }

    private static String labelsWith(Map<String, Map<String, Object>> cells, String status) {
        List<String> out = new ArrayList<>();
        for (Metric m : Metric.values()) {
            Map<String, Object> c = cells.get(m.name());
            if (c != null && status.equals(String.valueOf(c.get("status")))) out.add(m.getLabel());
        }
        return String.join("、", out);
    }

    private static String rule(MetricItem t) {
        if (t.targetValue() == null) return "未设";
        return ("GE".equals(t.compareOp()) ? "≥" : "≤") + num(t.targetValue()) + unitSuffix(t);
    }

    private static String unitSuffix(MetricItem t) {
        if (t == null || t.unit() == null || t.unit().isBlank()) return "";
        return "%".equals(t.unit()) ? "%" : " " + t.unit();
    }

    private static String num(Double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String pct(Double v) {
        return BigDecimal.valueOf(v * 100).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    /** 超过企业微信长度上限时截断（按字节） */
    private static String limit(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= MAX_BYTES) return s;
        String cut = new String(b, 0, MAX_BYTES - 40, StandardCharsets.UTF_8);
        return cut.substring(0, Math.max(0, cut.length() - 1)) + "\n……（内容太长，已截断，请到系统里查看）";
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
