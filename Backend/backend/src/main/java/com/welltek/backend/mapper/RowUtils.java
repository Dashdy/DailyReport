package com.welltek.backend.mapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 【结果集取值小工具】处理 NULL 列，避免每个 Mapper 都写一遍判空
final class RowUtils {

    private RowUtils() {
    }

    static LocalDateTime dateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    static LocalDate date(ResultSet rs, String column) throws SQLException {
        Date d = rs.getDate(column);
        return d == null ? null : d.toLocalDate();
    }

    /** DECIMAL(18,4) → Double，去掉多余的 0（3.1000 → 3.1） */
    static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.stripTrailingZeros().doubleValue();
    }
}
