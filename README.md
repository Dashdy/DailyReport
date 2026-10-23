# DailyReport 每日战报系统

工厂各工序“六项自主保证”（安全、品质、交付、报废、成本、设备）的每日战报系统：
工序负责人每天填写走势表，系统自动判定达标情况，首页汇总全厂达标率，所有修改留痕可追溯。

- 后端：Spring Boot 3.2.5 + JdbcTemplate + SQL Server（Java 17）
- 前端：Vue 3 + Vue CLI 5 + Element Plus + ECharts
- 开发环境与 [Dashboard](https://github.com/Dashdy/Dashboard) 项目保持一致

## 目录

| 目录 | 内容 |
|---|---|
| `Backend/backend` | 后端（端口 8083） |
| `Frontend/frontend` | 前端（开发端口 8084） |
| `deploy/` | Nginx 配置、正式环境配置模板 |
| `scripts/` | 部署脚本、数据库脚本，使用方法见 `scripts/说明.txt` |

## 主要功能

- **登录与权限**：系统管理员 / 管理员 / 用户三级；用户有用户名（显示）、账号（登录）、密码。
- **全厂达标汇总**：六项指标达标率、工序 × 指标达标矩阵、当月每日达标率走势。
- **工序战报**：25 道工序卡片，一眼看出每道工序哪几项未达标、未填报。
- **工序详情**：六项自主保证书、每日走势表（直接在表格里填）、六项折线图、班组看板（照片上传）。
- **数据留存**：每次修改记录操作人、原数据、新数据；每次保存生成当天战报的留底版本。
- **数据锁定**：历史数据锁定、当天锁定时间，可在系统设置里配置。
- **系统管理**（普通用户不可见）：用户管理、工序负责人分配、工序配置、系统设置、操作日志。

## 本地运行

后端（需要 JDK 17，本地数据库密码放在 `Backend/backend/config/application-local.properties`，不提交）：

```
cd Backend/backend
mvnw spring-boot:run
```

前端：

```
cd Frontend/frontend
npm install
npm run serve
```

浏览器打开 http://localhost:8084 。空库第一次启动会自动建表，并创建初始账号
`系统管理员 / admin`、`叶丰龙 / 0625`（账号 / 密码），登录后请修改密码。

## 部署

正式服务器 10.10.99.5（与 Dashboard 同一台），访问 http://10.10.99.5:8085 。
第一次安装运行 `scripts/install-on-server.ps1`，之后更新运行 `scripts/deploy-to-prod.ps1`，
详见 `scripts/说明.txt`。
