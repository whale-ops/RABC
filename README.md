# RBAC多租户企业管理平台

企业 RBAC 管理脚手架：Java 17、Spring Boot 3、MyBatis-Plus 3.5、MySQL、Redis、Sa-Token；前端 Vue 3 + Semi UI Vue + Axios。

当前已实现并联调系统管理的用户、角色、菜单、部门、岗位，以及认证、个人中心、AOP 登录/操作日志、OSS / MinIO 文件管理、系统监控、首页真实 PV / UV 访问统计和业务管理的订单、产品模块。

- [后台配置与启动](backend/README.md)
- [前端启动与模式说明](front/README.md)
- [实际接口、权限与联调说明](doc/10.系统管理后台与联调.md)
- [联调验收记录](doc/11.系统管理联调验收记录.md)
- [完整平台设计文档](doc/README.md)

已有 MySQL / Redis：填写 `backend/.env`，执行 `backend/scripts/dev.ps1 -SkipInfrastructure`。
使用独立本地容器：执行 `backend/scripts/dev.ps1`。另开终端在 `front` 执行 `npm ci`、`npm run dev`。

当前数据库采用 SQL 文件初始化。新空库首次启动加 `-InitializeDatabase`，普通启动不加；基础模块使用 `db/schema.sql`、`db/data.sql`，日志、文件、业务模块分别使用 `db/log-*.sql`、`db/storage-*.sql`、`db/business-*.sql`。已有库只执行所需模块的 schema、data 脚本，均不清空已有数据；已移除 Flyway。

真实模式初始账号为 `star / admin / StarAdmin@2026`，可在个人中心修改密码；已有数据库密码不会被初始化脚本覆盖。
