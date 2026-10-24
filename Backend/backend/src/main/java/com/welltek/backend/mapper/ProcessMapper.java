package com.welltek.backend.mapper;

import com.welltek.backend.entity.ProcessStep;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

// 【工序】dbo.PROCESS_STEP
// 已删除的工序（DELETED_AT 有值）在 selectAll 里不出现，但 selectById / selectByName 照样查得到（看历史、防重名）
@Repository
public class ProcessMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "ID, NAME, SORT_ORDER, ACTIVE, DELETED_AT";

    private static final RowMapper<ProcessStep> ROW_MAPPER = (rs, i) -> new ProcessStep(
            rs.getInt("ID"), rs.getString("NAME"), rs.getInt("SORT_ORDER"), rs.getBoolean("ACTIVE"),
            rs.getTimestamp("DELETED_AT") != null);

    /** 没删除的工序；includeInactive=false 时只要启用的 */
    public List<ProcessStep> selectAll(boolean includeInactive) {
        String where = " WHERE DELETED_AT IS NULL" + (includeInactive ? "" : " AND ACTIVE = 1");
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.PROCESS_STEP" + where + " ORDER BY SORT_ORDER, ID", ROW_MAPPER);
    }

    /** 已删除的工序（工序配置页“已删除的工序”列表），最近删除的在前 */
    public List<Map<String, Object>> selectDeleted() {
        return jdbcTemplate.query("SELECT ID, NAME, DELETED_AT, DELETED_BY FROM dbo.PROCESS_STEP WHERE DELETED_AT IS NOT NULL ORDER BY DELETED_AT DESC",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("ID"));
                    m.put("name", rs.getString("NAME"));
                    m.put("deletedAt", RowUtils.dateTime(rs, "DELETED_AT"));
                    m.put("deletedBy", rs.getString("DELETED_BY"));
                    return m;
                });
    }

    public ProcessStep selectById(Integer id) {
        List<ProcessStep> list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.PROCESS_STEP WHERE ID = ?", ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public ProcessStep selectByName(String name) {
        List<ProcessStep> list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.PROCESS_STEP WHERE NAME = ?", ROW_MAPPER, name);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 全部工序数（含已删除），DataInitializer 用来判断是不是第一次部署 */
    public int count() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dbo.PROCESS_STEP", Integer.class);
        return n == null ? 0 : n;
    }

    public int maxSortOrder() {
        Integer n = jdbcTemplate.queryForObject("SELECT ISNULL(MAX(SORT_ORDER), 0) FROM dbo.PROCESS_STEP WHERE DELETED_AT IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    public Integer insert(String name, int sortOrder) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dbo.PROCESS_STEP (NAME, SORT_ORDER, ACTIVE) VALUES (?, ?, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setInt(2, sortOrder);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).intValue();
    }

    public void update(Integer id, String name, int sortOrder, boolean active) {
        jdbcTemplate.update("UPDATE dbo.PROCESS_STEP SET NAME = ?, SORT_ORDER = ?, ACTIVE = ? WHERE ID = ?",
                name, sortOrder, active, id);
    }

    /** 删除工序：只做标记（同时停用），数据全部留在库里 */
    public void markDeleted(Integer id, String operator) {
        jdbcTemplate.update("UPDATE dbo.PROCESS_STEP SET DELETED_AT = SYSDATETIME(), DELETED_BY = ?, ACTIVE = 0 WHERE ID = ?", operator, id);
    }

    /** 恢复已删除的工序：回到工序配置列表，并重新启用 */
    public void restore(Integer id) {
        jdbcTemplate.update("UPDATE dbo.PROCESS_STEP SET DELETED_AT = NULL, DELETED_BY = NULL, ACTIVE = 1 WHERE ID = ?", id);
    }

    /** 每道工序的负责人：processId → [{id, username}] */
    public Map<Integer, List<Map<String, Object>>> selectOwners() {
        Map<Integer, List<Map<String, Object>>> map = new HashMap<>();
        jdbcTemplate.query("SELECT up.PROCESS_ID, u.ID, u.ACCOUNT, u.USERNAME FROM dbo.USER_PROCESS up " +
                "JOIN dbo.SYS_USER u ON u.ID = up.USER_ID ORDER BY u.ID", rs -> {
            Map<String, Object> owner = new LinkedHashMap<>();
            owner.put("id", rs.getInt("ID"));
            owner.put("account", rs.getString("ACCOUNT"));
            owner.put("username", rs.getString("USERNAME"));
            map.computeIfAbsent(rs.getInt("PROCESS_ID"), k -> new ArrayList<>()).add(owner);
        });
        return map;
    }
}
