package com.welltek.backend.mapper;

import com.welltek.backend.entity.Metric;
import com.welltek.backend.entity.MetricTarget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.*;

// 【六项自主保证书：公司底线 / 班组自定】dbo.METRIC_TARGET（判定规则在子项 dbo.METRIC_ITEM 里）
@Repository
public class TargetMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "PROCESS_ID, METRIC, BASELINE, TEAM_TARGET, UPDATED_BY, UPDATED_AT";

    private static final RowMapper<MetricTarget> ROW_MAPPER = (rs, i) -> new MetricTarget(
            rs.getInt("PROCESS_ID"),
            Metric.valueOf(rs.getString("METRIC")),
            rs.getString("BASELINE"),
            rs.getString("TEAM_TARGET"),
            rs.getString("UPDATED_BY"),
            RowUtils.dateTime(rs, "UPDATED_AT"));

    /** 某道工序的六项目标：metric → target */
    public Map<Metric, MetricTarget> selectByProcess(Integer processId) {
        Map<Metric, MetricTarget> map = new EnumMap<>(Metric.class);
        jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.METRIC_TARGET WHERE PROCESS_ID = ?", ROW_MAPPER, processId)
                .forEach(t -> map.put(t.metric(), t));
        return map;
    }

    /** 全部工序的目标：processId → (metric → target) */
    public Map<Integer, Map<Metric, MetricTarget>> selectAll() {
        Map<Integer, Map<Metric, MetricTarget>> map = new HashMap<>();
        jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.METRIC_TARGET", ROW_MAPPER)
                .forEach(t -> map.computeIfAbsent(t.processId(), k -> new EnumMap<>(Metric.class)).put(t.metric(), t));
        return map;
    }

    public void insert(MetricTarget t) {
        jdbcTemplate.update("INSERT INTO dbo.METRIC_TARGET (PROCESS_ID, METRIC, BASELINE, TEAM_TARGET, UPDATED_BY, UPDATED_AT) " +
                        "VALUES (?, ?, ?, ?, ?, SYSDATETIME())",
                t.processId(), t.metric().name(), t.baseline(), t.teamTarget(), t.updatedBy());
    }

    public void update(MetricTarget t) {
        jdbcTemplate.update("UPDATE dbo.METRIC_TARGET SET BASELINE = ?, TEAM_TARGET = ?, UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() " +
                        "WHERE PROCESS_ID = ? AND METRIC = ?",
                t.baseline(), t.teamTarget(), t.updatedBy(), t.processId(), t.metric().name());
    }
}
