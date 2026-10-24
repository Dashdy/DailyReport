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
        addUserWecomId();
        addProcessDeleted();
        addMetricItems();
    }

    private boolean hasColumn(String table, String column) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID(?) AND name = ?", Integer.class, table, column);
        return n != null && n > 0;
    }

    private boolean hasConstraint(String name) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys.objects WHERE name = ? AND type = 'UQ'", Integer.class, name);
        return n != null && n > 0;
    }

    /** 2026-10-24：工序可以“删除”（只做标记，数据保留），工序表加 DELETED_AT / DELETED_BY 列 */
    private void addProcessDeleted() {
        if (!hasColumn("dbo.PROCESS_STEP", "DELETED_AT")) {
            jdbcTemplate.execute("ALTER TABLE dbo.PROCESS_STEP ADD DELETED_AT DATETIME2 NULL");
            log.info("已升级工序表：新增 DELETED_AT（删除时间）列");
        }
        if (!hasColumn("dbo.PROCESS_STEP", "DELETED_BY")) {
            jdbcTemplate.execute("ALTER TABLE dbo.PROCESS_STEP ADD DELETED_BY NVARCHAR(50) NULL");
        }
    }

    /**
     * 2026-10-24：六项指标可以拆子项（dbo.METRIC_ITEM，表由 schema.sql 建好）。
     * 老库升级：
     * 1. 每道工序每项指标，按原来保证书里的 单位 / 判定 / 目标值 建一个不带名字的子项；
     * 2. 走势表 DAILY_RECORD 加 ITEM_ID 列，老数据挂到对应的子项上；
     * 3. 唯一约束从“工序 + 日期 + 指标”换成“子项 + 日期”。
     * 每一步都能重复执行：中途出错的话，下次启动会从没做完的那步接着做。
     */
    private void addMetricItems() {
        int created = jdbcTemplate.update(
                "INSERT INTO dbo.METRIC_ITEM (PROCESS_ID, METRIC, NAME, UNIT, COMPARE_OP, TARGET_VALUE, SORT_ORDER, ACTIVE, UPDATED_BY, UPDATED_AT) " +
                        "SELECT t.PROCESS_ID, t.METRIC, NULL, t.UNIT, ISNULL(t.COMPARE_OP, 'LE'), t.TARGET_VALUE, 0, 1, '系统升级', SYSDATETIME() " +
                        "FROM dbo.METRIC_TARGET t WHERE NOT EXISTS " +
                        "(SELECT 1 FROM dbo.METRIC_ITEM i WHERE i.PROCESS_ID = t.PROCESS_ID AND i.METRIC = t.METRIC)");
        if (created > 0) {
            log.info("已升级六项目标：按原来的判定规则建了 {} 个子项", created);
        }

        if (!hasColumn("dbo.DAILY_RECORD", "ITEM_ID")) {
            jdbcTemplate.execute("ALTER TABLE dbo.DAILY_RECORD ADD ITEM_ID INT NULL");
            log.info("已升级走势表：新增 ITEM_ID（子项）列");
        }
        // 有数据但没有目标行的（理论上没有），也补一个子项，保证每条老数据都挂得上
        jdbcTemplate.update(
                "INSERT INTO dbo.METRIC_ITEM (PROCESS_ID, METRIC, COMPARE_OP, SORT_ORDER, ACTIVE, UPDATED_BY, UPDATED_AT) " +
                        "SELECT DISTINCT r.PROCESS_ID, r.METRIC, 'LE', 0, 1, '系统升级', SYSDATETIME() FROM dbo.DAILY_RECORD r " +
                        "WHERE r.ITEM_ID IS NULL AND NOT EXISTS " +
                        "(SELECT 1 FROM dbo.METRIC_ITEM i WHERE i.PROCESS_ID = r.PROCESS_ID AND i.METRIC = r.METRIC)");
        int linked = jdbcTemplate.update(
                "UPDATE r SET ITEM_ID = (SELECT MIN(i.ID) FROM dbo.METRIC_ITEM i WHERE i.PROCESS_ID = r.PROCESS_ID AND i.METRIC = r.METRIC) " +
                        "FROM dbo.DAILY_RECORD r WHERE r.ITEM_ID IS NULL");
        if (linked > 0) {
            log.info("已升级走势表：{} 条老数据挂到了对应的子项上", linked);
        }

        Integer nullable = jdbcTemplate.queryForObject(
                "SELECT CAST(is_nullable AS INT) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.DAILY_RECORD') AND name = 'ITEM_ID'", Integer.class);
        if (nullable != null && nullable == 1) {
            jdbcTemplate.execute("ALTER TABLE dbo.DAILY_RECORD ALTER COLUMN ITEM_ID INT NOT NULL");
        }
        if (hasConstraint("UQ_DAILY_RECORD")) {
            jdbcTemplate.execute("ALTER TABLE dbo.DAILY_RECORD DROP CONSTRAINT UQ_DAILY_RECORD");
        }
        if (!hasConstraint("UQ_DAILY_RECORD_ITEM")) {
            jdbcTemplate.execute("ALTER TABLE dbo.DAILY_RECORD ADD CONSTRAINT UQ_DAILY_RECORD_ITEM UNIQUE (ITEM_ID, REPORT_DATE)");
            log.info("已升级走势表：唯一约束改成“子项 + 日期”");
        }
    }

    /** 2026-10-24：企业微信推送，用户表加“企业微信账号”列 */
    private void addUserWecomId() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys.columns WHERE object_id = OBJECT_ID('dbo.SYS_USER') AND name = 'WECOM_USERID'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE dbo.SYS_USER ADD WECOM_USERID NVARCHAR(64) NULL");
        log.info("已升级用户表：新增 WECOM_USERID（企业微信账号）列");
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
