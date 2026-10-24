package com.welltek.backend.mapper;

import com.welltek.backend.entity.Metric;
import com.welltek.backend.entity.MetricItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;

// 【指标子项】dbo.METRIC_ITEM
@Repository
public class ItemMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "ID, PROCESS_ID, METRIC, NAME, UNIT, COMPARE_OP, TARGET_VALUE, SORT_ORDER, ACTIVE, UPDATED_BY, UPDATED_AT";

    private static final RowMapper<MetricItem> ROW_MAPPER = (rs, i) -> new MetricItem(
            rs.getInt("ID"),
            rs.getInt("PROCESS_ID"),
            Metric.valueOf(rs.getString("METRIC")),
            rs.getString("NAME"),
            rs.getString("UNIT"),
            rs.getString("COMPARE_OP"),
            RowUtils.decimal(rs, "TARGET_VALUE"),
            rs.getInt("SORT_ORDER"),
            rs.getBoolean("ACTIVE"),
            rs.getString("UPDATED_BY"),
            RowUtils.dateTime(rs, "UPDATED_AT"));

    /** 某道工序的有效子项（已删除的不算），按 指标 → 排序 */
    public List<MetricItem> selectByProcess(Integer processId) {
        return sort(jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.METRIC_ITEM WHERE PROCESS_ID = ? AND ACTIVE = 1",
                ROW_MAPPER, processId));
    }

    /** 全部工序的有效子项：processId → 子项列表 */
    public Map<Integer, List<MetricItem>> selectAll() {
        Map<Integer, List<MetricItem>> map = new HashMap<>();
        for (MetricItem item : sort(jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.METRIC_ITEM WHERE ACTIVE = 1", ROW_MAPPER))) {
            map.computeIfAbsent(item.processId(), k -> new ArrayList<>()).add(item);
        }
        return map;
    }

    public Integer insert(MetricItem t) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dbo.METRIC_ITEM (PROCESS_ID, METRIC, NAME, UNIT, COMPARE_OP, TARGET_VALUE, SORT_ORDER, ACTIVE, UPDATED_BY, UPDATED_AT) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, SYSDATETIME())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, t.processId());
            ps.setString(2, t.metric().name());
            ps.setString(3, t.name());
            ps.setString(4, t.unit());
            ps.setString(5, t.compareOp());
            if (t.targetValue() == null) ps.setNull(6, Types.DECIMAL);
            else ps.setDouble(6, t.targetValue());
            ps.setInt(7, t.sortOrder());
            ps.setString(8, t.updatedBy());
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).intValue();
    }

    public void update(MetricItem t) {
        jdbcTemplate.update("UPDATE dbo.METRIC_ITEM SET NAME = ?, UNIT = ?, COMPARE_OP = ?, TARGET_VALUE = ?, SORT_ORDER = ?, " +
                        "UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() WHERE ID = ?",
                t.name(), t.unit(), t.compareOp(), t.targetValue(), t.sortOrder(), t.updatedBy(), t.id());
    }

    /** 删除子项：只是不再显示、不参与判定，已经填过的数据留在库里 */
    public void deactivate(Integer id, String operator) {
        jdbcTemplate.update("UPDATE dbo.METRIC_ITEM SET ACTIVE = 0, UPDATED_BY = ?, UPDATED_AT = SYSDATETIME() WHERE ID = ?", operator, id);
    }

    private static List<MetricItem> sort(List<MetricItem> list) {
        List<MetricItem> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing(MetricItem::metric).thenComparingInt(MetricItem::sortOrder).thenComparing(MetricItem::id));
        return sorted;
    }
}
