package com.welltek.backend.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 【企业微信推送记录】dbo.PUSH_LOG：只增不改
@Repository
public class PushLogMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void insert(Integer processId, LocalDate date, String kind, String trigger, String operator,
                       String channels, String contentHash, boolean success, String message) {
        jdbcTemplate.update("INSERT INTO dbo.PUSH_LOG (PROCESS_ID, REPORT_DATE, KIND, TRIGGER_TYPE, OPERATOR_NAME, CHANNELS, CONTENT_HASH, SUCCESS, MESSAGE, CREATED_AT) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, SYSDATETIME())",
                processId, Date.valueOf(date), kind, trigger, operator, channels, contentHash, success,
                message == null ? null : message.substring(0, Math.min(1000, message.length())));
    }

    /** 某工序某天最近一次推送成功时的内容指纹；没推过返回 null */
    public String lastSuccessHash(Integer processId, LocalDate date) {
        List<String> list = jdbcTemplate.queryForList(
                "SELECT TOP 1 CONTENT_HASH FROM dbo.PUSH_LOG WHERE PROCESS_ID = ? AND REPORT_DATE = ? AND KIND = 'PROCESS' AND SUCCESS = 1 ORDER BY ID DESC",
                String.class, processId, Date.valueOf(date));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 某工序某天的推送记录（详情页显示“今天已推送”用），新的在前 */
    public List<Map<String, Object>> selectByProcess(Integer processId, LocalDate date) {
        return query("SELECT TOP 10 * FROM dbo.PUSH_LOG WHERE PROCESS_ID = ? AND REPORT_DATE = ? ORDER BY ID DESC",
                processId, Date.valueOf(date));
    }

    /** 最近的推送记录（系统设置页用） */
    public List<Map<String, Object>> selectRecent(int limit) {
        return query("SELECT TOP " + Math.max(1, Math.min(limit, 200)) + " l.*, p.NAME AS PROCESS_NAME FROM dbo.PUSH_LOG l " +
                "LEFT JOIN dbo.PROCESS_STEP p ON p.ID = l.PROCESS_ID ORDER BY l.ID DESC");
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("ID"));
            m.put("processId", rs.getObject("PROCESS_ID"));
            if (hasColumn(rs, "PROCESS_NAME")) {
                m.put("processName", rs.getString("PROCESS_NAME"));
            }
            m.put("date", RowUtils.date(rs, "REPORT_DATE"));
            m.put("kind", rs.getString("KIND"));
            m.put("trigger", rs.getString("TRIGGER_TYPE"));
            m.put("operator", rs.getString("OPERATOR_NAME"));
            m.put("channels", rs.getString("CHANNELS"));
            m.put("success", rs.getBoolean("SUCCESS"));
            m.put("message", rs.getString("MESSAGE"));
            m.put("createdAt", RowUtils.dateTime(rs, "CREATED_AT"));
            return m;
        }, args);
    }

    private static boolean hasColumn(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        var md = rs.getMetaData();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            if (name.equalsIgnoreCase(md.getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }
}
