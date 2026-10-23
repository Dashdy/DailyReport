package com.welltek.backend.mapper;

import com.welltek.backend.entity.Role;
import com.welltek.backend.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

// 【用户 + 用户负责的工序】dbo.SYS_USER / dbo.USER_PROCESS
@Repository
public class UserMapper {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String COLUMNS = "ID, ACCOUNT, USERNAME, PASSWORD, ROLE, ENABLED, REMARK, CREATED_AT, UPDATED_AT";

    private static final RowMapper<User> ROW_MAPPER = (rs, i) -> new User(
            rs.getInt("ID"),
            rs.getString("ACCOUNT"),
            rs.getString("USERNAME"),
            rs.getString("PASSWORD"),
            Role.valueOf(rs.getString("ROLE")),
            rs.getBoolean("ENABLED"),
            rs.getString("REMARK"),
            RowUtils.dateTime(rs, "CREATED_AT"),
            RowUtils.dateTime(rs, "UPDATED_AT"));

    public User selectById(Integer id) {
        List<User> list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.SYS_USER WHERE ID = ?", ROW_MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 登录、查重都按账号查 */
    public User selectByAccount(String account) {
        List<User> list = jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.SYS_USER WHERE ACCOUNT = ?", ROW_MAPPER, account);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 排序：系统管理员 → 管理员 → 用户，同级按创建先后 */
    public List<User> selectAll() {
        return jdbcTemplate.query("SELECT " + COLUMNS + " FROM dbo.SYS_USER " +
                "ORDER BY CASE ROLE WHEN 'SYS_ADMIN' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, ID", ROW_MAPPER);
    }

    public int countEnabledByRole(Role role) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dbo.SYS_USER WHERE ROLE = ? AND ENABLED = 1", Integer.class, role.name());
        return n == null ? 0 : n;
    }

    public Integer insert(String account, String username, String encodedPassword, Role role, boolean enabled, String remark) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dbo.SYS_USER (ACCOUNT, USERNAME, PASSWORD, ROLE, ENABLED, REMARK, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?, SYSDATETIME())",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, account);
            ps.setString(2, username);
            ps.setString(3, encodedPassword);
            ps.setString(4, role.name());
            ps.setBoolean(5, enabled);
            ps.setString(6, remark);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).intValue();
    }

    public void update(Integer id, String account, String username, Role role, boolean enabled, String remark) {
        jdbcTemplate.update("UPDATE dbo.SYS_USER SET ACCOUNT = ?, USERNAME = ?, ROLE = ?, ENABLED = ?, REMARK = ?, UPDATED_AT = SYSDATETIME() WHERE ID = ?",
                account, username, role.name(), enabled, remark, id);
    }

    public void updatePassword(Integer id, String encodedPassword) {
        jdbcTemplate.update("UPDATE dbo.SYS_USER SET PASSWORD = ?, UPDATED_AT = SYSDATETIME() WHERE ID = ?", encodedPassword, id);
    }

    public void delete(Integer id) {
        jdbcTemplate.update("DELETE FROM dbo.USER_PROCESS WHERE USER_ID = ?", id);
        jdbcTemplate.update("DELETE FROM dbo.SYS_USER WHERE ID = ?", id);
    }

    // ---------------- 负责工序 ----------------

    public Set<Integer> selectProcessIds(Integer userId) {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT PROCESS_ID FROM dbo.USER_PROCESS WHERE USER_ID = ?", Integer.class, userId));
    }

    /** 所有用户的负责工序：userId → [processId] */
    public Map<Integer, List<Integer>> selectAllProcessIds() {
        Map<Integer, List<Integer>> map = new HashMap<>();
        jdbcTemplate.query("SELECT USER_ID, PROCESS_ID FROM dbo.USER_PROCESS", rs -> {
            map.computeIfAbsent(rs.getInt("USER_ID"), k -> new ArrayList<>()).add(rs.getInt("PROCESS_ID"));
        });
        return map;
    }

    /** 整体替换某个用户负责的工序 */
    public void replaceProcesses(Integer userId, Collection<Integer> processIds) {
        jdbcTemplate.update("DELETE FROM dbo.USER_PROCESS WHERE USER_ID = ?", userId);
        for (Integer pid : new LinkedHashSet<>(processIds)) {
            jdbcTemplate.update("INSERT INTO dbo.USER_PROCESS (USER_ID, PROCESS_ID) VALUES (?, ?)", userId, pid);
        }
    }

    /** 整体替换某道工序的负责人 */
    public void replaceOwners(Integer processId, Collection<Integer> userIds) {
        jdbcTemplate.update("DELETE FROM dbo.USER_PROCESS WHERE PROCESS_ID = ?", processId);
        for (Integer uid : new LinkedHashSet<>(userIds)) {
            jdbcTemplate.update("INSERT INTO dbo.USER_PROCESS (USER_ID, PROCESS_ID) VALUES (?, ?)", uid, processId);
        }
    }
}
