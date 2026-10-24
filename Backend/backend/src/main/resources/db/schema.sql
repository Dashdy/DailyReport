-- =========================================================================
-- 每日战报系统 建表脚本（spring.sql.init 每次启动执行）
-- 每条都是"表不存在才建"，已有的表和数据不会被改动或清空。
-- 初始账号、25 道工序、六项默认目标由 DataInitializer.java 写入（密码要 BCrypt 加密）。
-- =========================================================================

-- 用户：ACCOUNT 是登录用的账号（唯一），USERNAME 是系统里显示的名字（可以重名）
-- ROLE = SYS_ADMIN（系统管理员）/ ADMIN（管理员）/ USER（用户）
-- 早期版本的库没有 ACCOUNT 列，由 SchemaMigrator.java 自动补上
IF OBJECT_ID('dbo.SYS_USER', 'U') IS NULL
CREATE TABLE dbo.SYS_USER (
    ID          INT IDENTITY(1,1) PRIMARY KEY,
    ACCOUNT     NVARCHAR(50)  NOT NULL CONSTRAINT UX_SYS_USER_ACCOUNT UNIQUE,
    USERNAME    NVARCHAR(50)  NOT NULL,
    PASSWORD    NVARCHAR(100) NOT NULL,
    ROLE        NVARCHAR(20)  NOT NULL,
    ENABLED     BIT           NOT NULL DEFAULT 1,
    WECOM_USERID NVARCHAR(64) NULL,     -- 企业微信通讯录里的“账号”，推送个人消息用

    REMARK      NVARCHAR(200) NULL,
    CREATED_AT  DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    UPDATED_AT  DATETIME2     NULL
);

-- 工序：停用（ACTIVE=0）= 还在“工序配置”里，只是不出现在选项卡和首页；
-- 删除（DELETED_AT 有值）= 从各个页面隐藏，这一行和它的历史数据都留在库里，可以在“工序配置”里恢复
-- 早期版本没有 DELETED_AT / DELETED_BY，由 SchemaMigrator.java 补上
IF OBJECT_ID('dbo.PROCESS_STEP', 'U') IS NULL
CREATE TABLE dbo.PROCESS_STEP (
    ID          INT IDENTITY(1,1) PRIMARY KEY,
    NAME        NVARCHAR(50) NOT NULL UNIQUE,
    SORT_ORDER  INT          NOT NULL DEFAULT 0,
    ACTIVE      BIT          NOT NULL DEFAULT 1,
    DELETED_AT  DATETIME2    NULL,
    DELETED_BY  NVARCHAR(50) NULL,
    CREATED_AT  DATETIME2    NOT NULL DEFAULT SYSDATETIME()
);

-- 工序负责人：只有负责人（及管理员）可以编辑该工序的数据
IF OBJECT_ID('dbo.USER_PROCESS', 'U') IS NULL
CREATE TABLE dbo.USER_PROCESS (
    USER_ID     INT NOT NULL,
    PROCESS_ID  INT NOT NULL,
    PRIMARY KEY (USER_ID, PROCESS_ID)
);

-- 六项自主保证书：公司底线 / 班组自定（给人看的文字）
-- UNIT / COMPARE_OP / TARGET_VALUE 是改版前的判定规则，2026-10-24 起挪到 METRIC_ITEM（子项），这三列不再使用
IF OBJECT_ID('dbo.METRIC_TARGET', 'U') IS NULL
CREATE TABLE dbo.METRIC_TARGET (
    ID            INT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT           NOT NULL,
    METRIC        NVARCHAR(20)  NOT NULL,
    BASELINE      NVARCHAR(100) NULL,
    TEAM_TARGET   NVARCHAR(100) NULL,
    UNIT          NVARCHAR(20)  NULL,
    COMPARE_OP    NVARCHAR(10)  NOT NULL DEFAULT 'LE',
    TARGET_VALUE  DECIMAL(18,4) NULL,
    UPDATED_BY    NVARCHAR(50)  NULL,
    UPDATED_AT    DATETIME2     NULL,
    CONSTRAINT UQ_METRIC_TARGET UNIQUE (PROCESS_ID, METRIC)
);

-- 指标子项：每项指标至少一个子项，可以拆成多个（如 成本 → 锡球、铜球），每个子项单独填数、单独判定红绿；
-- 一项指标的所有子项都达标，这项才算达标。只有一个子项时 NAME 为空（页面上就显示指标名）。
-- 删除子项是 ACTIVE=0（不再显示、不参与判定），已经填过的数据留在库里。
-- COMPARE_OP：LE 当前值≤目标为达标，GE 当前值≥目标为达标
IF OBJECT_ID('dbo.METRIC_ITEM', 'U') IS NULL
CREATE TABLE dbo.METRIC_ITEM (
    ID            INT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT           NOT NULL,
    METRIC        NVARCHAR(20)  NOT NULL,
    NAME          NVARCHAR(30)  NULL,
    UNIT          NVARCHAR(20)  NULL,
    COMPARE_OP    NVARCHAR(10)  NOT NULL DEFAULT 'LE',
    TARGET_VALUE  DECIMAL(18,4) NULL,
    SORT_ORDER    INT           NOT NULL DEFAULT 0,
    ACTIVE        BIT           NOT NULL DEFAULT 1,
    UPDATED_BY    NVARCHAR(50)  NULL,
    UPDATED_AT    DATETIME2     NULL,
    INDEX IX_METRIC_ITEM (PROCESS_ID, METRIC)
);

-- 每日走势表的每一格：某工序某天某个子项的数值（METRIC 冗余存一份，方便按指标统计）
-- 早期版本没有 ITEM_ID，由 SchemaMigrator.java 补上，并把老数据挂到对应的子项上
IF OBJECT_ID('dbo.DAILY_RECORD', 'U') IS NULL
CREATE TABLE dbo.DAILY_RECORD (
    ID            BIGINT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT           NOT NULL,
    REPORT_DATE   DATE          NOT NULL,
    METRIC        NVARCHAR(20)  NOT NULL,
    ITEM_ID       INT           NOT NULL,
    METRIC_VALUE  DECIMAL(18,4) NOT NULL,
    CREATED_BY    NVARCHAR(50)  NULL,   -- 首次填报人
    CREATED_AT    DATETIME2     NULL,
    UPDATED_BY    NVARCHAR(50)  NULL,   -- 最后保存人
    UPDATED_AT    DATETIME2     NULL,
    CONSTRAINT UQ_DAILY_RECORD_ITEM UNIQUE (ITEM_ID, REPORT_DATE),
    INDEX IX_DAILY_RECORD_DATE (REPORT_DATE)
);

-- 每日行动与亮点：某工序某天的 今日行动措施 / 今日亮点 / 明日行动计划（工序详情页四功能小组下面三栏）
IF OBJECT_ID('dbo.DAILY_NOTE', 'U') IS NULL
CREATE TABLE dbo.DAILY_NOTE (
    ID            BIGINT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT            NOT NULL,
    REPORT_DATE   DATE           NOT NULL,
    ACTIONS       NVARCHAR(1000) NULL,   -- 今日行动措施
    HIGHLIGHTS    NVARCHAR(1000) NULL,   -- 今日亮点
    PLANS         NVARCHAR(1000) NULL,   -- 明日行动计划
    UPDATED_BY    NVARCHAR(50)   NULL,
    UPDATED_AT    DATETIME2      NULL,
    CONSTRAINT UQ_DAILY_NOTE UNIQUE (PROCESS_ID, REPORT_DATE)
);

-- 每日战报留底：每次保存后，把该工序当天的完整战报（六项数值 + 目标 + 达标情况）存一个新版本
IF OBJECT_ID('dbo.DAILY_SNAPSHOT', 'U') IS NULL
CREATE TABLE dbo.DAILY_SNAPSHOT (
    ID            BIGINT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT           NOT NULL,
    PROCESS_NAME  NVARCHAR(50)  NULL,
    REPORT_DATE   DATE          NOT NULL,
    VERSION       INT           NOT NULL,
    CONTENT       NVARCHAR(MAX) NULL,
    SAVED_BY      NVARCHAR(50)  NULL,
    SAVED_AT      DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    INDEX IX_DAILY_SNAPSHOT (PROCESS_ID, REPORT_DATE)
);

-- 班组看板：四功能小组四个人的照片、姓名、职能 + 全员签名 + 日期
-- （早期版本的 GROUP_NAME / GROUP_PHOTO / ACTION1~3 / MEMBER_PHOTO 列在老库里保留不删，页面不再使用）
IF OBJECT_ID('dbo.PROCESS_BOARD', 'U') IS NULL
CREATE TABLE dbo.PROCESS_BOARD (
    PROCESS_ID    INT           PRIMARY KEY,
    MEMBERS       NVARCHAR(MAX) NULL,   -- 四功能小组四个人：[{photo, name, role}]（JSON）
    SIGNERS       NVARCHAR(500) NULL,
    SIGN_DATE     DATE          NULL,
    UPDATED_BY    NVARCHAR(50)  NULL,
    UPDATED_AT    DATETIME2     NULL
);

-- 操作日志：谁、什么时候、改了什么、原数据、新数据
IF OBJECT_ID('dbo.AUDIT_LOG', 'U') IS NULL
CREATE TABLE dbo.AUDIT_LOG (
    ID             BIGINT IDENTITY(1,1) PRIMARY KEY,
    OPERATOR_ID    INT           NULL,
    OPERATOR_NAME  NVARCHAR(50)  NULL,
    MODULE         NVARCHAR(20)  NULL,
    ACTION         NVARCHAR(20)  NULL,
    PROCESS_NAME   NVARCHAR(50)  NULL,
    TARGET         NVARCHAR(200) NULL,
    BEFORE_VALUE   NVARCHAR(MAX) NULL,
    AFTER_VALUE    NVARCHAR(MAX) NULL,
    CREATED_AT     DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    INDEX IX_AUDIT_LOG_TIME (CREATED_AT)
);

-- 系统设置（键值对），例如 UPLOAD_DIR：图片存储路径
IF OBJECT_ID('dbo.SYS_SETTING', 'U') IS NULL
CREATE TABLE dbo.SYS_SETTING (
    SETTING_KEY    NVARCHAR(50)   PRIMARY KEY,
    SETTING_VALUE  NVARCHAR(2000) NULL,
    UPDATED_BY     NVARCHAR(50)   NULL,
    UPDATED_AT     DATETIME2      NULL
);

-- 企业微信推送记录：每推一次记一条（工序战报 PROCESS_ID 有值；全厂汇总 PROCESS_ID 为空）
-- CONTENT_HASH 用来判断“手动推过之后数据有没有变”，没变的话定时推送跳过
IF OBJECT_ID('dbo.PUSH_LOG', 'U') IS NULL
CREATE TABLE dbo.PUSH_LOG (
    ID            BIGINT IDENTITY(1,1) PRIMARY KEY,
    PROCESS_ID    INT           NULL,
    REPORT_DATE   DATE          NOT NULL,
    KIND          NVARCHAR(20)  NOT NULL,   -- PROCESS / SUMMARY / TEST
    TRIGGER_TYPE  NVARCHAR(20)  NOT NULL,   -- MANUAL / AUTO / TEST
    OPERATOR_NAME NVARCHAR(50)  NULL,
    CHANNELS      NVARCHAR(100) NULL,       -- 实际发出去的渠道，如“ID助手 3 人 / 群机器人”
    CONTENT_HASH  NVARCHAR(64)  NULL,
    SUCCESS       BIT           NOT NULL,
    MESSAGE       NVARCHAR(1000) NULL,
    CREATED_AT    DATETIME2     NOT NULL DEFAULT SYSDATETIME(),
    INDEX IX_PUSH_LOG (REPORT_DATE, PROCESS_ID)
);

-- 登录 Token（存库，后端重启不掉线）
IF OBJECT_ID('dbo.USER_TOKEN', 'U') IS NULL
CREATE TABLE dbo.USER_TOKEN (
    TOKEN       NVARCHAR(64) PRIMARY KEY,
    USER_ID     INT          NOT NULL,
    EXPIRES_AT  DATETIME2    NOT NULL,
    CREATED_AT  DATETIME2    NOT NULL DEFAULT SYSDATETIME()
);
