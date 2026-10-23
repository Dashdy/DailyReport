package com.welltek.backend.mapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 【每日战报留底】dbo.DAILY_SNAPSHOT：只增不改，每保存一次就多一个版本
@Repository
public class SnapshotMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void insert(Integer processId, String processName, LocalDate date, String content, String savedBy) {
        Integer next = jdbcTemplate.queryForObject(
                "SELECT ISNULL(MAX(VERSION), 0) + 1 FROM dbo.DAILY_SNAPSHOT WHERE PROCESS_ID = ? AND REPORT_DATE = ?",
                Integer.class, processId, Date.valueOf(date));
        jdbcTemplate.update("INSERT INTO dbo.DAILY_SNAPSHOT (PROCESS_ID, PROCESS_NAME, REPORT_DATE, VERSION, CONTENT, SAVED_BY, SAVED_AT) " +
                        "VALUES (?, ?, ?, ?, ?, ?, SYSDATETIME())",
                processId, processName, Date.valueOf(date), next, content, savedBy);
    }

    /** 某道工序某天的全部历史版本，新的在前 */
    public List<Map<String, Object>> selectByProcessAndDate(Integer processId, LocalDate date) {
        return jdbcTemplate.query("SELECT ID, REPORT_DATE, VERSION, CONTENT, SAVED_BY, SAVED_AT FROM dbo.DAILY_SNAPSHOT " +
                        "WHERE PROCESS_ID = ? AND REPORT_DATE = ? ORDER BY VERSION DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("ID"));
                    m.put("date", RowUtils.date(rs, "REPORT_DATE"));
                    m.put("version", rs.getInt("VERSION"));
                    m.put("content", rs.getString("CONTENT"));
                    m.put("savedBy", rs.getString("SAVED_BY"));
                    m.put("savedAt", RowUtils.dateTime(rs, "SAVED_AT"));
                    return m;
                }, processId, Date.valueOf(date));
    }
}
