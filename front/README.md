# StarAdmin Front

Vue 3 + TypeScript + **Semi UI Vue**（`@kousum/semi-ui-vue`）+ **Axios**。
页面风格来自根目录原型；系统管理五个模块已接通 `../backend` 的 Spring Boot 后台。

## 默认：真实后台

先按 [后台启动说明](../backend/README.md) 配置并启动 MySQL、Redis 和 API，再启动前端：

```powershell
cd D:\JavaCode\VibeCoding\front
npm ci
npm run dev
```

默认 http://127.0.0.1:5173，`/api` 代理到 http://127.0.0.1:8080。
Node.js 22.12+，推荐 24。不要把数据库或 Redis 密码放进 VITE_ 环境变量。

```dotenv
VITE_USE_MOCK=false
VITE_API_BASE_URL=/api/v1
VITE_API_PROXY_TARGET=http://127.0.0.1:8080
```

默认不需要复制 env 文件；自定义时创建 `.env.local`。已有数据库账号使用企业分配的账号；
独立开发容器初始账号是 `star / admin / StarAdmin@2026`，前提是使用后台脚本自动生成的演示配置。

## 已接通页面

| 页面 | 能力 |
| --- | --- |
| 用户 | 查询、部门筛选、服务端分页/排序、详情、新增编辑、多角色/岗位、状态、删除/批量删除、密码重置、导出 |
| 角色 | CRUD、状态、页面/操作授权、五种数据范围、自定义部门 |
| 菜单 | 目录/页面/按钮、上级关系、路由/权限码、排序、启停、CRUD |
| 部门 | 上级、负责人、成员数、状态、CRUD，后端防循环与引用保护 |
| 岗位 | 名称编码、排序、状态、CRUD，后端引用保护 |
| 认证与个人中心 | 登录、退出、刷新恢复、权限导航、资料保存、个人中心安全设置与真实密码修改 |
| 首页 | 权限范围内的用户/角色/部门/岗位统计、本人最近操作 |

管理数据由 MySQL 持久化，角色/部门/岗位均按 ID 关联；不会将真实业务记录写入 localStorage。
角色/菜单安全配置、新增用户及角色授权限租户管理员。按钮可见性对应后端权限，服务端做最终授权。

真实模式只显示已接通的菜单。项目、文件、字典、参数、日志管理和监控后台属于后续开发范围。

## Axios 与会话

- `src/api/request.ts`：统一 Axios 实例、15 秒超时、Bearer、请求 ID、标准响应解包、统一错误及 Blob 错误解析。
- `src/api/auth.ts`：登录、CSRF、刷新、退出、个人资料和改密。访问令牌只存内存，刷新 Cookie 为 HttpOnly。
- 页面刷新恢复登录；受保护请求 401 后 single-flight 刷新并最多重试一次。支持 Web Locks 的浏览器会串行跨标签页刷新。
- `src/api/session.ts`：当前身份、动态菜单与按钮权限。
- `src/api/system-live.ts`：五模块真实 API；页面只发送允许的写入字段和 version。
- `SystemManagement.vue`：服务端列表、真实关联选项、加载/空/错误态、并发冲突提示和表单操作。
- `SystemHome.vue`：真实统计；原型趋势图保留在演示模式。

## 构建和验证

```powershell
npm run build       # TypeScript 检查 + Vite 生产构建
npm run typecheck
npm run test:live   # 真实后台已启动；专用开发/测试库
npm run test:e2e    # 原型 Mock 模式回归，单独端口 5175
```

Windows 测试使用已安装的 Edge；其他平台先运行 `npx playwright install chromium`。
`test:live` 使用 5174 端口并请求真实 8080 后台，会创建/修改专用测试数据，不要对生产数据库执行。
浏览器截图位于 `qa/`。早期 dashboard.png 是原型演示；`live-*.png` 为真实联调截图，包含首页、五个管理页面和移动端用户列表。

构建输出 `dist/`。部署需为 Vue History 路由配置 `try_files $uri $uri/ /index.html;`；`/api` 应代理后端，不应回退 HTML。
生产同源 HTTPS，后端开启 Secure Cookie 并配置准确的来源白名单。

## 单独查看原型演示

没有后台时可显式设置：

```powershell
$env:VITE_USE_MOCK='true'
npm run dev
```

此模式完整保留原型首页和其他尚未开发后台的页面，使用 localStorage / IndexedDB。
默认可以直接进入首页；退出后演示账号 `admin / admin123`。
该密码只属于 Mock 模式，与真实后台密码无关。

恢复真实模式前删除该环境变量并重启 Vite：
`Remove-Item Env:VITE_USE_MOCK`。

## 素材与依赖

- 首页插画是本项目绘制的 SVG；登录插画来自 [Storyset Dashboard / Amico](https://storyset.com/illustration/dashboard/amico)，登录及帮助页保留署名，遵循 [Storyset 条款](https://storyset.com/terms)。
- Semi UI Vue 使用 [项目文档](https://semi-ui-vue.netlify.app/zh-cn/start/getting-started/)对应的真实 Vue 组件；`src/ui.ts` 集中按需导出。
- Semi 的间接拖拽依赖声明了较窄的 Vue peer 版本，安装可能提示范围警告；当前未使用拖拽组件，依赖锁定在 package-lock.json。
- Axios 用法参考[官方实例文档](https://axios-http.com/docs/instance)。

完整接口和当前实现范围见 [系统管理后台与联调](../doc/10.系统管理后台与联调.md)。


日志管理已接入真实后台：`/audit/operations`、`/audit/logins` 使用 `AuditLogs.vue` 和 Axios，支持关键字、结果、操作类型、时间段、分页、详情和 XLSX 导出。日志菜单由后台授权返回；未获授权的账号不显示入口。需先在已有库执行 backend/src/main/resources/db/log-schema.sql、log-data.sql，并重启后端。原型模式仍使用演示数据页面，日常联调保持 VITE_USE_MOCK=false。


文件管理 `/files` 已接入真实后台：StorageFiles.vue 通过 api/storage.ts 调用统一上传、分页查询、下载和删除接口。按钮按 storage:file 权限展示；文件保存在服务端配置的 OSS / MinIO，浏览器不保存内容或云凭据。需执行后台 storage-schema.sql / storage-data.sql 并重登。原型模式 Files.vue 仍使用 IndexedDB，附件管理仍为待开放原型，不混入真实文件。
