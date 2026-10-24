package com.welltek.backend.mapper;

import com.welltek.backend.entity.DailyNote;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

// 【每日行动与亮点】dbo.DAILY_NOTE：一道工序一天一行
@Repository
public class NoteMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "PROCESS_ID, REPORT_DATE, ACTIONS, HIGHLIGHTS, PLANS, UPDATED_BY, UPDATED_AT";

    private static final RowMapper<DailyNote> ROW_MAPPER = (rs, i) -> new DailyNote(
            rs.getInt("PROCESS_ID"),
            RowUtils.date(rs, "REPORT_DATE"),
            rs.getString("ACTIONS"),
            rs.getString("HIGHLIGHTS"),
            rs.getString("PLANS"),
            rs.getString("UPDATED_BY"),
            RowUtils.dateTime(rs, "UPDATED_AT"));

    /** 某道工序在 [from, to] 日期范围内的全部记录 */
    public List<DailyNote> selectByProcess(Integer processId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.DAILY_NOTE WHERE PROCESS_ID = ? AND REPORT_DATE BETWEEN ? AND ? ORDER BY REPORT_DATE",
                ROW_MAPPER, processId, Date.valueOf(from), Date.valueOf(to));
    }

    public DailyNote selectOne(Integer processId, LocalDate date) {
        List<DailyNote> list = selectByProcess(processId, date, date);
        return list.isEmpty() ? null : list.get(0);
    }

    public void upsert(DailyNote n) {
        int updated = jdbcTemplate.update("UPDATE dbo.DAILY_NOTE SET ACTIONS = ?, HIGHLIGHTS = ?, PLANS = ?, UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() " +
                        "WHERE PROCESS_ID = ? AND REPORT_DATE = ?",
                n.actions(), n.highlights(), n.plans(), n.updatedBy(), n.processId(), Date.valueOf(n.reportDate()));
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO dbo.DAILY_NOTE (PROCESS_ID, REPORT_DATE, ACTIONS, HIGHLIGHTS, PLANS, UPDATED_BY, UPDATED_AT) " +
                            "VALUES (?, ?, ?, ?, ?, ?, SYSDATETIME())",
                    n.processId(), Date.valueOf(n.reportDate()), n.actions(), n.highlights(), n.plans(), n.updatedBy());
        }
    }
}
