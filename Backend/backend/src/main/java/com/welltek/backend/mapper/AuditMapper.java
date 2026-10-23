package com.welltek.backend.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 【操作日志】dbo.AUDIT_LOG：只增不改不删
@Repository
public class AuditMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void insert(Integer operatorId, String operatorName, String module, String action,
                       String processName, String target, String before, String after) {
        jdbcTemplate.update("INSERT INTO dbo.AUDIT_LOG (OPERATOR_ID, OPERATOR_NAME, MODULE, ACTION, PROCESS_NAME, TARGET, BEFORE_VALUE, AFTER_VALUE, CREATED_AT) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, SYSDATETIME())",
                operatorId, operatorName, module, action, processName, target, before, after);
    }

    /**
     * 分页查询，条件都可以为空。
     * modules 限定只查这几个模块（工序详情页只给看填数 / 目标 / 看板的记录）；
     * keyword 匹配操作人或操作对象；endDate 包含当天。
     */
    public Map<String, Object> selectPage(int page, int size, List<String> modules, String module, String processName,
                                          String keyword, LocalDate startDate, LocalDate endDate) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (modules != null && !modules.isEmpty()) {
            where.append(" AND MODULE IN (").append(String.join(", ", Collections.nCopies(modules.size(), "?"))).append(")");
            params.addAll(modules);
        }
        if (module != null && !module.isBlank()) {
            where.append(" AND MODULE = ?");
            params.add(module);
        }
        if (processName != null && !processName.isBlank()) {
            where.append(" AND PROCESS_NAME = ?");
            params.add(processName);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (OPERATOR_NAME LIKE ? OR TARGET LIKE ?)");
            params.add("%" + keyword.trim() + "%");
            params.add("%" + keyword.trim() + "%");
        }
        if (startDate != null) {
            where.append(" AND CREATED_AT >= ?");
            params.add(Date.valueOf(startDate));
        }
        if (endDate != null) {
            where.append(" AND CREATED_AT < ?");
            params.add(Date.valueOf(endDate.plusDays(1)));
        }

        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.AUDIT_LOG" + where, Long.class, params.toArray());

        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add((Math.max(page, 1) - 1) * size);
        pageParams.add(size);
        List<Map<String, Object>> rows = jdbcTemplate.query(
                "SELECT ID, OPERATOR_NAME, MODULE, ACTION, PROCESS_NAME, TARGET, BEFORE_VALUE, AFTER_VALUE, CREATED_AT FROM dbo.AUDIT_LOG" +
                        where + " ORDER BY ID DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("ID"));
                    m.put("operatorName", rs.getString("OPERATOR_NAME"));
                    m.put("module", rs.getString("MODULE"));
                    m.put("action", rs.getString("ACTION"));
                    m.put("processName", rs.getString("PROCESS_NAME"));
                    m.put("target", rs.getString("TARGET"));
                    m.put("beforeValue", rs.getString("BEFORE_VALUE"));
                    m.put("afterValue", rs.getString("AFTER_VALUE"));
                    m.put("createdAt", RowUtils.dateTime(rs, "CREATED_AT"));
                    return m;
                }, pageParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("list", rows);
        return result;
    }
}
