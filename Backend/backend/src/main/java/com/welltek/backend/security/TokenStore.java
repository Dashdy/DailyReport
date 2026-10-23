package com.welltek.backend.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 【登录 Token】存在 dbo.USER_TOKEN 里，后端重启不会把所有人踢下线。
// 采用“滑动过期”：每次带着 Token 访问接口都会把过期时间往后续，
// 只有连续 app.token-idle-hours 小时没有操作才需要重新登录。
@Component
public class TokenStore {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.token-idle-hours:12}")
    private long idleHours;

    public String issueToken(Integer userId) {
        // 顺手清理已经过期的 Token，表不会越积越大
        jdbcTemplate.update("DELETE FROM dbo.USER_TOKEN WHERE EXPIRES_AT < SYSDATETIME()");

        String token = "DR_" + UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO dbo.USER_TOKEN (TOKEN, USER_ID, EXPIRES_AT) VALUES (?, ?, ?)",
                token, userId, Timestamp.valueOf(LocalDateTime.now().plusHours(idleHours)));
        return token;
    }

    /** Token 有效则续期并返回用户 ID；无效 / 过期返回 null */
    public Integer validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        List<Integer> ids = jdbcTemplate.queryForList(
                "SELECT USER_ID FROM dbo.USER_TOKEN WHERE TOKEN = ? AND EXPIRES_AT > SYSDATETIME()",
                Integer.class, token);
        if (ids.isEmpty()) {
            return null;
        }
        jdbcTemplate.update("UPDATE dbo.USER_TOKEN SET EXPIRES_AT = ? WHERE TOKEN = ?",
                Timestamp.valueOf(LocalDateTime.now().plusHours(idleHours)), token);
        return ids.get(0);
    }

    public void invalidate(String token) {
        if (token != null) {
            jdbcTemplate.update("DELETE FROM dbo.USER_TOKEN WHERE TOKEN = ?", token);
        }
    }

    /** 用户被删除 / 停用 / 重置密码时，踢掉他所有已登录的会话 */
    public void invalidateUser(Integer userId) {
        jdbcTemplate.update("DELETE FROM dbo.USER_TOKEN WHERE USER_ID = ?", userId);
    }
}
