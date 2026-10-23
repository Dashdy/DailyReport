package com.welltek.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// 【老库结构升级】schema.sql 只负责“表不存在才建”，已经建好的表要加列、改约束就在这里做。
// 每一步都先检查再执行，重复启动不会重复改。
@Component
public class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void migrate() {
        addUserAccount();
        addBoardMembers();
    }

    /**
     * 2026-10-23：班组看板改成“四功能小组四个人的照片 + 姓名 + 职能”，新增 MEMBERS 列（JSON）。
     * 原来的小组名字、三条动作、组员签名照片列保留在库里不删，页面不再显示。
     */
    private void addBoardMembers() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.PROCESS_BOARD') AND name = 'MEMBERS'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE dbo.PROCESS_BOARD ADD MEMBERS NVARCHAR(MAX) NULL");
        log.info("已升级班组看板表：新增 MEMBERS（四功能小组成员）列");
    }

    /**
     * 2026-10-23：用户拆成“用户名 / 账号 / 密码”三个字段。
     * 老库只有 USERNAME（既用来登录又用来显示），加上 ACCOUNT 列，并把现有用户的账号设成原来的用户名，
     * 这样大家原来怎么登录现在还怎么登录；同时去掉 USERNAME 的唯一约束（显示名允许重名，账号不允许）。
     */
    private void addUserAccount() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.SYS_USER') AND name = 'ACCOUNT'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE dbo.SYS_USER ADD ACCOUNT NVARCHAR(50) NULL");
        jdbcTemplate.execute("UPDATE dbo.SYS_USER SET ACCOUNT = USERNAME");
        jdbcTemplate.execute("ALTER TABLE dbo.SYS_USER ALTER COLUMN ACCOUNT NVARCHAR(50) NOT NULL");
        jdbcTemplate.execute("ALTER TABLE dbo.SYS_USER ADD CONSTRAINT UX_SYS_USER_ACCOUNT UNIQUE (ACCOUNT)");

        List<String> constraints = jdbcTemplate.queryForList(
                "SELECT kc.name FROM sys.key_constraints kc " +
                        "JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id " +
                        "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id " +
                        "WHERE kc.parent_object_id = OBJECT_ID('dbo.SYS_USER') AND kc.type = 'UQ' AND c.name = 'USERNAME'",
                String.class);
        for (String name : constraints) {
            jdbcTemplate.execute("ALTER TABLE dbo.SYS_USER DROP CONSTRAINT [" + name.replace("]", "]]") + "]");
        }
        log.info("已升级用户表：新增 ACCOUNT（账号）列，现有用户的账号 = 原用户名");
    }
}
