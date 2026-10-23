package com.welltek.backend.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 【系统设置】dbo.SYS_SETTING，键值对
@Repository
public class SettingMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 返回 {value, updatedBy, updatedAt}，没有这个设置返回 null */
    public Map<String, Object> select(String key) {
        List<Map<String, Object>> list = jdbcTemplate.query(
                "SELECT SETTING_VALUE, UPDATED_BY, UPDATED_AT FROM dbo.SYS_SETTING WHERE SETTING_KEY = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value", rs.getString("SETTING_VALUE"));
                    m.put("updatedBy", rs.getString("UPDATED_BY"));
                    m.put("updatedAt", RowUtils.dateTime(rs, "UPDATED_AT"));
                    return m;
                }, key);
        return list.isEmpty() ? null : list.get(0);
    }

    public String selectValue(String key) {
        Map<String, Object> m = select(key);
        return m == null ? null : (String) m.get("value");
    }

    public void upsert(String key, String value, String updatedBy) {
        int n = jdbcTemplate.update("UPDATE dbo.SYS_SETTING SET SETTING_VALUE = ?, UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() WHERE SETTING_KEY = ?",
                value, updatedBy, key);
        if (n == 0) {
            jdbcTemplate.update("INSERT INTO dbo.SYS_SETTING (SETTING_KEY, SETTING_VALUE, UPDATED_BY, UPDATED_AT) VALUES (?, ?, ?, SYSDATETIME())",
                    key, value, updatedBy);
        }
    }
}
