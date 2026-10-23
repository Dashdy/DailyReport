package com.welltek.backend.mapper;

import com.welltek.backend.entity.DailyRecord;
import com.welltek.backend.entity.Metric;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

// 【每日走势表数据】dbo.DAILY_RECORD
@Repository
public class RecordMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "ID, PROCESS_ID, REPORT_DATE, METRIC, METRIC_VALUE, CREATED_BY, CREATED_AT, UPDATED_BY, UPDATED_AT";

    private static final RowMapper<DailyRecord> ROW_MAPPER = (rs, i) -> new DailyRecord(
            rs.getLong("ID"),
            rs.getInt("PROCESS_ID"),
            RowUtils.date(rs, "REPORT_DATE"),
            Metric.valueOf(rs.getString("METRIC")),
            RowUtils.decimal(rs, "METRIC_VALUE"),
            rs.getString("CREATED_BY"),
            RowUtils.dateTime(rs, "CREATED_AT"),
            rs.getString("UPDATED_BY"),
            RowUtils.dateTime(rs, "UPDATED_AT"));

    /** 某道工序在 [from, to] 日期范围内的全部数据 */
    public List<DailyRecord> selectByProcess(Integer processId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.DAILY_RECORD " +
                        "WHERE PROCESS_ID = ? AND REPORT_DATE BETWEEN ? AND ? ORDER BY REPORT_DATE",
                ROW_MAPPER, processId, Date.valueOf(from), Date.valueOf(to));
    }

    /** 全厂所有工序在 [from, to] 日期范围内的数据（首页汇总用） */
    public List<DailyRecord> selectAll(LocalDate from, LocalDate to) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.DAILY_RECORD WHERE REPORT_DATE BETWEEN ? AND ?",
                ROW_MAPPER, Date.valueOf(from), Date.valueOf(to));
    }

    public DailyRecord selectOne(Integer processId, LocalDate date, Metric metric) {
        List<DailyRecord> list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.DAILY_RECORD " +
                        "WHERE PROCESS_ID = ? AND REPORT_DATE = ? AND METRIC = ?",
                ROW_MAPPER, processId, Date.valueOf(date), metric.name());
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(Integer processId, LocalDate date, Metric metric, Double value, String operator) {
        jdbcTemplate.update("INSERT INTO dbo.DAILY_RECORD (PROCESS_ID, REPORT_DATE, METRIC, METRIC_VALUE, CREATED_BY, CREATED_AT, UPDATED_BY, UPDATED_AT) " +
                        "VALUES (?, ?, ?, ?, ?, SYSDATETIME(), ?, SYSDATETIME())",
                processId, Date.valueOf(date), metric.name(), value, operator, operator);
    }

    public void updateValue(Long id, Double value, String operator) {
        jdbcTemplate.update("UPDATE dbo.DAILY_RECORD SET METRIC_VALUE = ?, UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() WHERE ID = ?",
                value, operator, id);
    }

    public void delete(Long id) {
        jdbcTemplate.update("DELETE FROM dbo.DAILY_RECORD WHERE ID = ?", id);
    }
}
