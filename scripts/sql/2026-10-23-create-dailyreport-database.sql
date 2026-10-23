-- 在正式服务器上创建 DailyReport 数据库（幂等：已存在就什么都不做）。
-- 表结构不需要在这里建：后端第一次启动时会执行 db/schema.sql 自动建表，
-- 并写入初始账号（系统管理员 / admin、叶丰龙 / 0625）和 25 道默认工序。
IF DB_ID(N'DailyReport') IS NULL
BEGIN
    CREATE DATABASE DailyReport COLLATE Chinese_PRC_CI_AS;
    PRINT N'已创建数据库 DailyReport';
END
ELSE
    PRINT N'数据库 DailyReport 已存在，跳过';
