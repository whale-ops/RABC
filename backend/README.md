# StarAdmin 系统管理后台

Java 17 + Spring Boot **3.5.16** + MyBatis-Plus **3.5.17** + MySQL 8 + Redis + Sa-Token **1.46.0**。
配套前端位于 `../front`，默认使用真实接口。

## 已实现

- 用户：服务端查询/分页/排序、详情、新增、编辑、删除/批量删除、启停、多角色/多岗位、重置密码、Excel 导入与导出。
- 角色：维护、Excel 批量导入、成员数量、菜单/按钮授权、五种数据范围、自定义部门；删除普通角色时只解除绑定、保留用户。
- 菜单：目录/页面/按钮、路由与权限码、父子关系、树接口、排序、启停、循环与关联校验。
- 部门：组织树、成员计数、负责人、上下级调整、循环检查、引用保护。
- 岗位：编码/名称唯一、维护、启停、关联引用保护。
- 登录/退出、CSRF、Redis 会话、刷新轮换/重放撤销、当前用户/动态菜单、个人资料、真实改密。
- UTC 存储、字符串 ID、租户复合外键、版本冲突 409、统一异常和请求编号、同事务成功操作审计。

## 启动

需要 JDK 17、Maven 3.9、Node.js 22.12+（推荐 24）、可连接的 MySQL / Redis。

### 使用现有 MySQL / Redis

本机已接入 `D:\Develop\MYSQL8` 的 MySQL 8.0.46（3306），项目专属库 `star_admin`、应用账号 `star_app`；Redis 使用 `D:\Develop\redis\Redis-8.10.2-Windows-x64-msys2-with-Service` 的 6379 端口、15 号逻辑库。实际凭据已写入 `.env`，无需再次复制示例覆盖。此库已按确认范围精简，只保留 star/admin、管理员角色、总经办及系统菜单权限；演示数据初始化已关闭。

Redis 当前由后台进程运行；电脑重启后可运行安装目录中的 `start.bat`，或在 PowerShell 中后台启动：

```powershell
$redisFolder = 'D:\Develop\redis\Redis-8.10.2-Windows-x64-msys2-with-Service'
Start-Process -FilePath "$redisFolder\redis-server.exe" -ArgumentList 'redis.conf' -WorkingDirectory $redisFolder -WindowStyle Hidden
```

本机启动后台请使用 `scripts/dev.ps1 -SkipInfrastructure`，避免再次启动独立容器。以下步骤用于配置新的环境：

1. 准备专属空数据库，例如：
   ```sql
   CREATE DATABASE star_admin CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
   不要指向其他系统的数据库。首次显式初始化通过 `db/schema.sql` 创建 `sys_*` 表。
2. 复制 `.env.example` 为 `.env`，填入实际连接参数。文件已被忽略提交。Spring Boot 不自动读取 dotenv，使用下列脚本会将它注入进程。
3. 新空库首次运行时加 `-InitializeDatabase`；已有库日常启动不加。`data.sql` 初始化 star/admin，开发初始密码为 `StarAdmin@2026`，可在个人中心修改密码。
4. PowerShell 7：
   ```powershell
   cd D:\JavaCode\VibeCoding\backend
   .\scripts\dev.ps1 -SkipInfrastructure
   ```

数据库账号在首次初始化时需要 DDL 权限。当前使用 schema.sql/data.sql，不再依赖 Flyway 或 Java 参数化初始化；普通启动 `DB_INIT_MODE=never`。新空库命令：`.\scripts\dev.ps1 -SkipInfrastructure -InitializeDatabase`。

### 登录页账号申请

登录页提供注册与忘记密码入口，采用管理员人工核实身份的方式，无需邮件服务。注册申请只保存 BCrypt 摘要，不直接创建账号；管理员在“系统管理 → 用户管理 → 账号申请”选择部门与普通角色后通过审核，才会创建并启用账号。处理后清除申请中的密码摘要。

注册成功响应包含 `requestId/status/message`，页面显示申请编号。租户不存在或停用返回 400，账号已存在返回 409；重复待审核申请返回原编号，保留原申请。管理员“账号申请”按钮显示待处理数量，每 15 秒和窗口重新获得焦点时更新，打开面板默认查看待处理申请。

用户工具栏按“新增用户、批量删除、导入、导出”排列。使用 EasyExcel 4.0.3 生成 `.xlsx` 模板、读取首个工作表进行预览，再确认整批新增；也兼容 CSV UTF-8。每批最多 100 行、文件最多 1 MB，可选默认部门和普通角色。导入沿用租户管理员及 `system:user:create` 权限，继承关联、角色与密码校验，任一行失败整批回滚。预览不写库，错误不回显密码。

系统管理的五类导出统一由 EasyExcel 生成 `.xlsx`，沿用原有筛选、排序、租户、权限和数据范围限制，最多 10,000 行。表头中文化，ID、手机号与类似公式的内容按文本保存；不导出密码或哈希。

角色管理同样在导出前提供导入入口，支持 Excel 模板、预览和 CSV UTF-8。列为角色名称、角色编码、数据范围、状态、备注，前两列必填，数据范围默认“仅本人”，状态默认“启用”。每批最多 100 行、文件最多 1 MB；仅允许租户管理员且具有 `system:role:create` 权限操作。所有角色按普通新增流程创建，不含成员或菜单授权，不覆盖已有角色；名称、编码重复或任何校验失败均整批回滚。自定义部门范围需导入后在权限设置中配置。

找回密码需填写账号绑定的邮箱和手机号，匹配后进入同一申请列表；公开接口统一返回受理提示，不透露账号是否存在。管理员通过企业内部渠道核实身份、设置新密码后告知本人，旧会话随即失效。内置管理员的密码仍由个人中心或系统维护人员处理。

已有库执行 `src/main/resources/db/schema.sql` 补建 `sys_account_request`；无需删除旧表或重复导入基础数据。申请接口保留 CSRF 和每个来源 IP 每分钟 5 次限流，审核接口限制租户管理员及现有用户管理权限。新账号及重置后的账号正常进入工作空间；修改密码位于“个人中心 → 安全设置”。

### 独立本地开发容器

```powershell
cd D:\JavaCode\VibeCoding\backend
.\scripts\dev.ps1 -InitializeDatabase
```

脚本在没有 `.env` 时生成随机 MySQL 密码，启动 Compose 并运行服务。上面命令只用于首次空库；以后省略 `-InitializeDatabase`。
仅监听本机：MySQL `13306`、Redis `16379`、API `8080`。已有 MySQL `3306` 不受影响。
Compose 卷保留数据；停止使用 `docker compose stop`，不要随意删除卷。

只初始化 `star/admin` 和必需的部门、角色、菜单权限。没有演示数据脚本和演示初始化开关。新空库可按 schema.sql → log-schema.sql → data.sql → log-data.sql 的顺序执行，不依赖会话变量。已有库新增日志模块只需执行 log-schema.sql → log-data.sql，不清空或重建已有表。

### 前端

另开终端：

```powershell
cd D:\JavaCode\VibeCoding\front
npm ci
npm run dev
```

访问终端显示的地址，通常为 http://127.0.0.1:5173。
`VITE_USE_MOCK=false` 为默认；`/api` 经 Vite 代理到 `http://127.0.0.1:8080`。
本地允许 `127.0.0.1` / `localhost` 的 5173、5174、5175 端口。Vite 在端口占用时会自动顺延，请以终端实际输出的地址为准；其他端口或域名需加入 `backend/.env` 的 `APP_PUBLIC_ORIGINS` 并重启后台。若没有刻意运行多个前端，先关闭重复的开发服务。来源必须完整匹配协议、主机和端口，否则登录会提示“请求来源不被允许”。

## 构建与验证

```powershell
# backend
mvn test
mvn package

# 加载 backend/.env 到当前终端后，验证 Spring Boot 启动、MySQL、Redis 和 HTTP
Get-Content .env | ForEach-Object {
  if ($_ -match '^([A-Z_][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
  }
}
mvn clean verify -Pintegration "-Dit.test=BootApplicationIT,AccountRequestsIT,AuditLogsIT,StorageFilesIT"

# front：先启动真实后台和数据库
npm run build
npm run test:live

# 独立前端原型回归；该命令单独启动 5175 端口的 Mock 前端
npm run test:e2e
```

真实集成测试位于 `front/tests-live`，使用实际 HTTP、MySQL、Redis 和 Edge（非 Windows 使用 Chromium）。该完整套件依赖演示双租户与账号，请在专门的演示测试库运行，不要在当前精简库中重新开启演示数据。Java SQL 初始化测试在事务中回滚测试数据。
测试专用前端端口 `5174`；默认使用独立开发实例的管理员账号。可用 `TEST_ADMIN_PASSWORD` 覆盖密码。
**只在专属开发/测试库运行测试**：它会创建临时用户、角色、部门等，并验证密码、授权和会话失效。
不要对生产或共享业务库运行此套件。

Windows 不能覆盖正在运行的 JAR。重新 `mvn package` 前先结束该 JAR 进程；日常开发可以使用 `mvn spring-boot:run`。

## 目录

```text
backend/
├─ pom.xml
├─ compose.yml
├─ .env.example
├─ scripts/dev.ps1
└─ src/
   ├─ main/java/com/star/
   │  ├─ StarApplication.java    # Spring Boot 启动入口
   │  ├─ auth/                  # 认证与账号申请
   │  │  ├─ controller/          # 登录、刷新、个人中心、申请审核接口
   │  │  ├─ dto/                 # AuthInputs：认证与账号申请校验模型
   │  │  └─ service/             # 会话、密码、个人资料与申请业务
   │  ├─ common/                # 统一响应、业务异常、异常处理
   │  ├─ config/                # Web 配置、MyBatis-Plus 配置
   │  ├─ framework/             # 跨模块基础能力
   │  │  ├─ annotation/          # LoginAudit、OperationAudit
   │  │  ├─ aspect/              # AuditLogAspect：统一请求审计
   │  │  ├─ filter/              # 请求编号与线程上下文清理
   │  │  └─ security/            # 当前身份、RBAC、数据范围
   │  ├─ monitor/               # 登录与操作日志业务（接口仍为 /api/v1/audit）
   │  │  ├─ controller/          # 日志查询、详情和导出接口
   │  │  ├─ model/               # AuditLogQuery、LogEvent
   │  │  └─ service/             # 日志查询、独立事务写入与匿名化
   │  ├─ storage/               # OSS / MinIO 对象存储
   │  │  ├─ config/             # 公共上传限制、密钥路径及存储适配器注册
   │  │  ├─ controller/         # 文件上传、列表、下载、删除接口
   │  │  ├─ enums/              # StorageProvider：厂商枚举
   │  │  ├─ exception/          # 存储异常转换，不回显 SDK 敏感信息
   │  │  ├─ factory/            # StorageStrategyFactory：注册和选择策略
   │  │  ├─ model/              # 文件展示、分页、下载流模型
   │  │  ├─ service/            # 权限、租户、文件索引、上传补偿
   │  │  └─ strategy/
   │  │     ├─ StorageStrategy.java
   │  │     └─ impl/            # AliyunOssStorageStrategy、MinioStorageStrategy
   │  ├─ system/                # 系统管理
   │  │  ├─ controller/         # 系统、首页、Excel 导入接口
   │  │  ├─ dto/                # 系统管理写入、导入、授权校验模型
   │  │  ├─ entity/             # 数据库实体
   │  │  ├─ mapper/             # MyBatis-Plus BaseMapper
   │  │  └─ service/            # 事务、数据关系、业务校验
   │  └─ tool/excel/            # EasyExcel 通用读写
   ├─ main/resources/
   │  ├─ application.yml         # 公共配置，默认 dev
   │  ├─ application-dev.yml     # 本地开发连接配置
   │  └─ db/
   │     ├─ schema.sql           # 系统管理建表
   │     ├─ data.sql             # 最小基础数据，无会话变量
   │     ├─ log-schema.sql       # 登录/操作日志建表
   │     ├─ log-data.sql         # 日志菜单与权限（可重复执行）
   │     ├─ storage-schema.sql   # 文件索引表
   │     └─ storage-data.sql     # 文件菜单、权限及旧版升级（可重复执行）
   └─ test/java/com/star/        # 密码测试与真实依赖启动集成测试
```

### 新模块开发约定

- 根包统一使用 `com.star`；先按业务划分模块，再按职责建立子包。包名使用小写英文。
- `controller` 负责 HTTP 参数、校验、响应和流关闭；业务规则、权限、事务进入 `service`。
- 请求模型放 `dto`；响应、分页、内部事件放 `model`；持久化实体和 MyBatis 接口分别放 `entity`、`mapper`。跨层模型单独定义，不嵌套在服务类中供控制器使用。
- 模块配置、枚举、异常分别放 `config`、`enums`、`exception`。存在可切换实现时，`strategy` 定义接口，`strategy.impl` 放厂商适配，`factory` 统一注册和选择策略。
- 横向切面、注解、请求过滤器和权限上下文放 `framework`；日志查询与持久化等业务仍放 `monitor`。通用 Excel 读写放 `tool.excel`。
- 使用显式的项目内 import；每个业务源码在 import 之后、类型 Javadoc 之前，分别用独立的 `// 包职责：…` 与 `// 模块联系：…` 中文注释说明所属包的用途和模块关系。类、接口、枚举及记录自身的说明保留在声明和注解之前的 Javadoc 中；包说明直接分散在对应源码，不创建 `package-info.java` 或集中说明文件。测试包路径与被测试实现保持一致。
- 包重组不修改接口 URL、权限码、表名和环境变量。移动文件后执行上面的 `clean verify` 命令，避免旧 `.class` 造成重复 Bean；已有开发库通过 `-Dit.test` 选择四项业务集成测试，`InitialDataSqlIT` 仅在专用空库执行。

## 权限与实现边界

- 所有业务查询与写操作显式包含会话租户，关系表使用复合外键；没有依赖前端传递 tenantId，也没有租户绕过开关。
- 角色/菜单安全配置、新增用户和用户角色授权仅限内置租户管理员。普通角色按权限码执行范围内的用户查询、编辑等操作；不能自行授予新角色。内置管理员身份不能通过普通接口新增分配。
- 用户数据范围按**当前操作权限**筛选角色后合并。没有该操作权限的角色不能用其 ALL 范围扩大另一个角色的范围。列表、详情、更新、删除、导出及汇总使用一致策略。
- 角色、菜单、部门、岗位是租户级配置，使用各自管理权限，不套用用户部门过滤。
- 写操作先锁定本租户行，再重新校验当前用户及权限；事务隔离为 READ COMMITTED，更新/删除带租户、范围和 version。该保守策略适合后台管理量级，后续高吞吐场景可细化锁粒度。
- 权限每次直接读取数据库，不使用授权缓存，因此没有缓存延迟撤权。密码/用户变更递增 authVersion；旧会话立即拒绝。Redis 故障时拒绝访问。
- Bearer 只保存在前端内存；刷新凭据使用 HttpOnly + SameSite=Strict Cookie，仅限 `/api/v1/auth`。刷新凭据摘要存 Redis，一次性消费、7 天绝对/24 小时空闲期；重放撤销整个 family。Sa-Token 自身会话按其 Redis 插件格式存储。
- 原有 `sys_audit` 保留首页历史操作。新增 AOP 日志模块将启用后的登录与操作写入独立表，支持查询、详情与 EasyExcel 导出；旧审计不会伪造 IP、耗时或补入新表。日志只读；告警和审计 outbox 尚未实现。
- 当前为物理删除，不存在回收站。在用户管理删除选中账号时，同事务删除该用户的资料、角色/岗位绑定以及关联注册和找回密码申请（按本租户 user_id 或账号匹配）；其他用户、角色、部门、岗位保留。被删除账号和旧会话随即失效。删除角色只解除该角色的用户绑定并清理角色权限/部门范围，保留所有用户。内置管理员、当前账号、版本冲突或其他校验失败会阻止整批删除。操作审计保留历史 ID；新增日志中的账号、姓名、IP 和客户端标识会随用户删除而匿名化，不记录邮箱、手机号或密码。部门/岗位仍按引用规则保护。
- 字典、参数、项目、业务附件关联和监控后台尚未实现。文件管理已接入 OSS / MinIO；真实模式导航按后台权限开放，原型模式仍可查看其他页面。
- `compose.yml` 是本地开发依赖。生产 HTTPS、Redis 认证、数据库备份、高可用与容量压测需按部署环境另行配置。生产必须 `COOKIE_SECURE=true`，使用独立强密码和正确的来源白名单。

接口字段、测试与原始设计差异见 [系统管理后台与联调](../doc/10.系统管理后台与联调.md)。


依赖参考：[Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[MyBatis-Plus Boot3 安装](https://baomidou.com/getting-started/install/)、[Sa-Token Redis 集成](https://github.com/dromara/Sa-Token/blob/master/sa-token-doc/up/integ-redis.md)。

## 登录日志与操作日志（AOP）

已有本地库执行 `src/main/resources/db/log-schema.sql`，再执行 `log-data.sql`；均可重复执行。前者创建 `sys_login_log` / `sys_operation_log`，后者为 star 租户添加日志菜单、详情和导出权限，仅为内置管理员初始化授权。新空库显式初始化已配置这两份脚本，普通启动仍不自动执行 SQL。

登录使用 `@LoginAudit` 环绕认证服务，记录已通过请求绑定校验的成功、密码错误、停用或限流等认证结果。操作使用控制器级 `@OperationAudit`，记录系统管理写操作、账号申请/审核、个人资料/密码修改和 Excel 导出；普通查询、日志详情、令牌刷新和预览不记为操作日志，避免重复与自循环。拦截器在进入控制器前拒绝的请求、JSON 绑定/参数校验失败不会进入操作切面。原始密码、摘要、令牌、Cookie、请求/响应正文均不序列化；异常仅记录安全错误码。

日志独立事务提交，业务回滚后仍保留失败记录。日志存储异常只输出请求编号与错误类型，不覆盖原业务响应；这属于尽力记录，尚未实现持久化补偿队列。IP 使用直连 remoteAddr，不信任客户端伪造的 X-Forwarded-For；浏览器/系统来自客户端标识，不伪造地理位置。时间按 UTC 存储，前端按本地时间展示。用户删除会匿名化该账号已有日志中的身份及网络信息；与写日志共享租户锁，避免并发请求重新写回已删除资料。

API（全部按会话租户隔离）：

- `GET /api/v1/audit/logins`、`GET /api/v1/audit/operations`：分页查询，参数 page/size、keyword、status（成功/失败）、action、start/end（带时区 ISO 时间）、direction（asc/desc）。
- `GET /api/v1/audit/{logins|operations}/{id}`：日志详情。
- `GET /api/v1/audit/{logins|operations}/export`：按相同条件导出 .xlsx，最多 10000 条。

权限分别为 `audit:login:list/read/export` 和 `audit:operation:list/read/export`；普通角色需管理员显式分配。未知租户的登录尝试存 tenant_id=NULL，不向任何租户的查询接口暴露。日志不提供新增、修改或删除接口。

验证：注入本地 .env 后运行 `mvn verify -Pintegration -Dit.test=AuditLogsIT,AccountRequestsIT,BootApplicationIT`；测试使用独立临时租户并清理，检查 AOP、回滚、租户隔离、敏感信息、权限和 XLSX 响应。


## 对象存储（策略 + 工厂）

侧边栏“文件管理”为可展开、收起的目录，下设“图片管理”(`/images`) 与“附件管理”(`/attachments`)，旧 `/files` 地址重定向到图片管理。进入任一子页面时会自动展开文件目录并高亮当前页面。图片范围为 PNG、JPG、JPEG、GIF、WEBP、BMP、AVIF（扩展名不区分大小写）；其他格式进入附件管理。分类按已有文件名计算，不改动历史索引和 OSS / MinIO 对象，两个页面分别分页、筛选和统计大小。

文件模块只保留 `src/main/resources/db/storage-schema.sql` 和 `storage-data.sql`：前者建表，后者统一完成菜单、权限初始化及旧版入口升级。已有文件模块的数据库只需执行 `storage-data.sql`，可重复执行；首次安装依次执行这两份脚本，新空库初始化配置已包含它们。重复执行保留菜单启停状态及已有角色的具体读写权限，不改动文件索引或存储对象。两个入口共用现有 `storage:file:list/upload/download/delete` 权限和租户、上传人隔离规则。`GET /api/v1/storage/files` 及上传接口支持 `category=images|videos|attachments|all`，省略时兼容原来的全部文件接口；显式指定分类上传时，后端在访问存储之前校验文件名格式，防止选错入口。扩展名仅用于管理分类，不代表文件内容验证。

“系统管理 → 系统配置 → 存储配置”以列表管理任意数量的命名连接，可新增、编辑、启停和移除。同类型可以连接不同服务器；当前上传下拉框来自已启用的配置列表。内置阿里云 OSS、MinIO、通用 S3 兼容存储、WebDAV 文件服务器、服务器本地目录五种协议，输入任意符合协议的服务器地址或绝对目录。保存后每次新上传直接读取数据库配置，不需要改 `.env` 或重启。新租户默认未配置且禁止上传，不能使用其他租户的存储凭据。

`StorageRouter` 为每次请求创建独立的策略和 SDK 生命周期；上传时把 `storage_config_id` 写入文件记录，预览、下载和删除始终使用该连接位置。修改 Endpoint、地域或 Bucket 时保存新的位置，旧文件保留原位置；同一位置更新凭据会作用于该位置的历史文件。正在执行的请求持有自己的连接快照，配置更新不会中途关闭旧客户端。下载流关闭时释放客户端，视频签名生成后立即释放。系统不自动搬迁对象、创建 Bucket 或修改 ACL。

配置分布在 `sys_storage_setting`（上传开关、active_entry_id、catalog_ready 和版本）、`sys_storage_entry`（命名配置入口、连接 ID、启停与归档标记）和 `sys_storage_profile`（历史连接位置与加密凭据），仍集中于 `storage-schema.sql`。已有库需增加 entry 表以及 setting 的 active_entry_id BIGINT NULL、catalog_ready BOOLEAN NOT NULL DEFAULT FALSE 两列；更早版本还需增加 profile 表和 file.storage_config_id 及其租户复合外键。本地库已升级。首次读取列表时按租户把原 OSS / MinIO 选择转换为命名入口，保持原上传位置与开关；移除入口不会清理历史连接或远端文件。普通启动仍不自动初始化数据库。

`GET /api/v1/system/config/storage/catalog` 返回列表、当前选择、版本以及适配器声明的类型/字段。`POST .../catalog` 新增，`PUT .../catalog/{id}` 编辑，输入 `{version,name,type,enabled,values}`；values 仅接受类型声明的字段。`PUT .../catalog/selection` 输入 `{version,enabled,entryId}`，`DELETE .../catalog/{id}?version=` 归档入口。全部限租户管理员，分别检查 system:config:read/update，版本冲突返回 409。响应只含非敏感字段和 secretsSet 标记，不返回明文或密文；密钥留空保留当前配置原值，首次配置必填。停用正在使用的入口会暂停新上传，当前入口在上传开启时不可直接移除。旧固定两厂商 API 只为未转换租户兼容，列表转换后拒绝旧保存，避免覆盖新选择。

类型和表单字段由 StorageBackendAdapter Bean 注册，StorageClientFactory 自动发现，前端无固定厂商表单。新增非内置协议需增加对应后端适配器及校验逻辑；普通 HTTP 网页地址不等于上传协议，也不执行管理员输入的代码。S3 兼容服务复用 S3 签名客户端；WebDAV 使用标准 MKCOL/PUT/GET/DELETE，基目录需存在，支持账号与密码/应用密码认证，不跟随重定向传递凭据。本地目录属于 Java 后端服务器，可为已挂载 NAS；必须为绝对路径，禁止盘符根目录、目录穿越及符号链接。

凭据使用 AES-256-GCM 加密，并绑定租户、连接 ID 与字段。部署密钥保存在不纳入版本管理的 `.runtime/storage-config.key`，也可通过 `STORAGE_CONFIG_KEY_FILE` 指定持久化文件路径；工程内的相对路径按 backend 模块解析，IDE 从工程根目录启动和脚本从 backend 启动会使用同一文件。迁移或部署时必须保留原密钥文件，数据库和密钥文件需配套恢复；已有密文而密钥缺失时拒绝生成替代密钥，避免静默覆盖导致历史凭据无法解密。API、公开登录配置和日志均不返回密钥。

日常部署的 `application.yml`、`.env.example` 和本地 `.env` 不再列出厂商地址、Bucket、凭据、上传开关或默认厂商；这些设置统一通过后台存储配置管理。配置文件仅保留凭据加密密钥文件路径、上传大小上限等部署参数。普通启动不从旧 OSS / MinIO 环境变量导入连接，也不覆盖后台已保存的配置；历史文件继续按数据库中绑定的原连接访问。维护尚未迁移的旧安装时，应先完成历史连接入库与文件绑定，再清理其旧部署配置。

MinIO 填写 S3 API 地址（通常 9000），9001 为控制台；OSS 使用官方 HTTPS 地域 Endpoint。Bucket 需要预先创建并赋予对象读写删除权限。地址中的 127.0.0.1 指 Java 后端所在机器；视频直连预览还要求浏览器可以访问该地址。“启用文件上传”关闭后禁止新上传，历史文件的读取、删除以及“仅移除记录”仍可操作。

API：

- `GET /api/v1/storage/files?keyword=&page=1&pageSize=12`：分页文件索引及符合条件的数量 / 总大小。
- `POST /api/v1/storage/files`：multipart 字段 `file` 上传，非空，单文件最大 20 MB；Excel 导入仍在自身接口限制 1 MB。
- `GET /api/v1/storage/files/{id}/content`：鉴权后以 attachment 二进制流下载，不生成永久公共 URL。
- `GET /api/v1/storage/files/{id}/preview`：鉴权后解析 XLS/XLSX、DOC/DOCX，返回只读预览数据；与下载使用相同的租户、上传人和 `storage:file:download` 权限，不调用第三方在线转换服务。
- `DELETE /api/v1/storage/files/{id}`：删除对象及索引。

权限为 `storage:file:list/upload/download/delete`。内置租户管理员管理本租户文件，普通用户需要对应权限且仅能访问本人上传的文件。文件名清洗后只用于展示与下载，真实对象键为 `tenants/{tenantId}/{UTC日期}/{UUID}.{扩展名}`，不能由客户端指定。上传人姓名实时关联用户表，删除账号后显示“已删除用户”；文件内容保留，需管理员单独删除。

在线预览：TXT 等文本兼容 UTF-8、带 BOM 的 UTF-16、GB18030/GBK，最多展示前 64 KiB；PDF 使用本地 PDF.js Worker 绘制，支持翻页、缩放与适应宽度，无需浏览器插件。Excel 使用 Apache POI 展示工作表及合并单元格，公式读取保存时的缓存值；Word 展示正文和表格，DOCX 支持常见内嵌图片。预览保持只读，复杂排版、批注、宏及编辑功能不在此范围内。每份工作簿最多预览 10 张表，每张 300 行、50 列；正文、图片和解压大小均有上限，超限会提示下载查看完整内容。解析结果由 Vue 文本插值渲染，禁止文档 HTML、宏和公式执行；最多同时解析两份 Office 文档。解析方式参考 [Apache POI 文档](https://poi.apache.org/components/)。

上传先写对象再事务提交索引，上传或数据库提交失败会尝试删除本次随机对象键；补偿失败记录对象位置供人工清理。普通删除先删对象再删索引，远端失败保留索引；数据库提交失败时可重试幂等删除。存储停用、配置失效或无法连接时，可在删除弹窗明确选择“仅移除记录”，调用 `DELETE /api/v1/storage/files/{id}/record`。该接口复用 `storage:file:delete`、租户和所有者校验，仅删除文件索引，不解析厂商配置、不访问远端；不会自动补删可能仍存在的对象。两种模式不会自动相互降级，操作日志分别标记“删除”和“移除文件记录”。MySQL 与对象存储没有分布式事务，进程崩溃、网络不确定性或补偿失败仍可能留下孤立对象，目前不含持久化补偿队列。不要把此流程视为强原子提交。

凭据仅保存在服务端环境变量，不写 SQL、不下发前端，也不记录 SDK 原始错误或请求签名。上传、删除复用 `@OperationAudit` 写入操作日志。SDK 参考：[OSS Java](https://help.aliyun.com/zh/oss/developer-reference/oss-java-sdk/)、[MinIO Java](https://minio-java.min.io/io/minio/MinioClient.html)。

测试：`mvn test` 覆盖工厂、厂商协议和提交失败补偿；注入 `.env` 后 `mvn verify -Pintegration -Dit.test=StorageFilesIT` 使用真实 MySQL / Redis 与存储策略桩验证接口、隔离和审计，不向生产云资源写入测试数据。

视频管理：文件管理目录下的 /videos 复用 StorageFiles 页面与 storage:file:* 权限，默认卡片并可切换列表。支持 MP4、WebM、OGV、MOV、M4V 上传，单文件上限 20 MB。`GET /api/v1/storage/files/{id}/playback` 先验证下载权限、租户及所有者，再按历史厂商签发 15 分钟有效的只读地址；浏览器直接使用 OSS / MinIO / S3 的 Range 请求按需读取，不经后端完整转发或 Blob 全量缓冲。只对视频格式提供地址，响应不缓存；不改变 Bucket 私有权限，不向前端发送密钥。签名 URL 含对象路径和临时访问授权，有效期内持有者可读取该视频，请勿保存到日志或持久化存储。过期时重新授权并恢复播放位置，关闭弹窗即停止请求。本地目录和 WebDAV 视频通过后端签发的 15 分钟只读随机票据分段读取，支持 GET/HEAD、单段 Range、206/416；票据只绑定一个文件，移除文件记录后失效。对象存储 endpoint 必须可由浏览器访问，HTTPS 页面应配置 HTTPS 存储地址。浏览器能否播放取决于编码，推荐 MP4（H.264/AAC），不提供服务端转码；不支持的编码显示下载提示。历史视频按扩展名自动进入视频分类，不再显示于附件列表，无需移动对象或修改索引。菜单升级复用 storage-data.sql。

系统监控已接入真实后台：

- `GET /api/v1/monitor/server`：权限 `monitor:server:read`，采集当前 Java 后端所在主机的 CPU、物理内存、磁盘、JVM 堆/线程/运行时间，以及 MySQL / Redis 连接健康；并非远程 MinIO 服务器的硬件状态。
- `GET /api/v1/monitor/cache`：权限 `monitor:cache:read`，读取 Redis INFO 中的白名单指标。内存、命中率、连接数和命令计数为整个 Redis 实例的统计，键数量和带过期时间键数量仅为配置的逻辑库。命中率在尚无访问样本时显示“—”。不返回原始 INFO、环境变量、键名、键值或凭据，不提供清空缓存功能。
- `GET /api/v1/monitor/sessions`：权限 `monitor:session:list`，支持 keyword/page/size；仅返回当前租户启用且认证版本有效的会话。最近十五分钟有认证请求视为在线，同一账号不同刷新家族各占一行，刷新访问令牌不增加行数。关闭浏览器不能立即等同于离线；显式退出会立即移除该会话。会话 ID 为家族 SHA-256 摘要，不是访问令牌，响应不包含原始凭据。每次会话最多十五秒更新一次活跃信息。
- `POST /api/v1/monitor/sessions/{id}/kick`：要求 `monitor:session:kick` 且为租户管理员，只撤销选中刷新家族，下次访问和刷新均返回会话失效，不删除账号或其他登录。保护当前会话及内置管理员账号，强退操作由 AOP 记录为“强退会话”。普通角色需要管理员显式授权监控菜单，不会自动取得监控权限。

每五秒采集实际指标，在内存中最多保留 144 个点（约十二分钟）；应用重启后重新积累，不生成模拟历史，不承诺跨重启或多实例统一历史。页面每十秒轮询，隐藏标签页暂停刷新，离开页面清理计时器及图表。指标暂不可用时展示错误或缺失值。Redis 在线索引只按租户查询，移除过期分数；单次最多处理最近活跃的 2000 个会话，超过时显式提示截断，不使用 KEYS 扫描整个实例。

菜单与权限集中在 `src/main/resources/db/monitor-data.sql`，已有库执行一次即可（脚本可重复运行，保留现有授权及菜单启停状态），新空库初始化顺序已纳入 application.yml。监控无需新增业务表；Redis 保存带 TTL 的在线会话元数据，运行历史保存在 JVM 内存中。Redis 账号需要允许 INFO 命令才能查看缓存指标，查询接口的权限检查与认证不可绕过。

## 首页与访问统计

真实模式首页按原型展示欢迎区、用户数量、PV / UV、后端运行时间、访问趋势折线图、操作模块分布环图、最近操作分页表、快捷入口与最新动态。图表支持近 7 / 30 / 90 天，读取真实接口，不填充演示历史。

已有库执行 `src/main/resources/db/dashboard-schema.sql` 创建 `sys_visit_daily`，不修改已有业务数据；新空库初始化已纳入该脚本。普通启动仍不自动执行 SQL。统计按北京时间归日，持久化到 MySQL，后端重启后保留。只有采集启用后的访问有数据，之前日期显示 0。

- `POST /api/v1/dashboard/visit`：登录后成功进入业务路由或刷新页面上报一个随机事件编号，身份及租户取自服务端会话。相同事件在 24 小时内通过 Redis 去重；自动轮询、查询统计、图表周期切换和重复导航不会新增 PV。采集失败不阻断页面，未登录的登录页不参与统计。
- `GET /api/v1/dashboard/analytics?days=7`：要求 `dashboard:read`。PV 是页面浏览次数，UV 是所选周期内去重的登录账号数；同账号跨浏览器仍算一个 UV，周期 UV 不等于每日 UV 相加。持有 `audit:login:list` 查看本租户访问及登录失败汇总，否则仅本人。
- `GET /api/v1/dashboard/overview`：要求 `dashboard:read`，返回可访问的用户/角色/部门/岗位数量、当前 Java 进程运行时间与最新操作；无资源查询权限的数量返回空值。
- `GET /api/v1/dashboard/activities?page=1&size=5`：要求 `dashboard:read`，读取 AOP 的 `sys_operation_log`，支持分页。最近操作与业务分布：持有 `audit:operation:list` 查看本租户，否则仅本人。快捷入口同时检查操作权限和可访问菜单。

日统计仅保存租户、日期、账号摘要和次数，不保存浏览 URL、IP、姓名或令牌；登录失败数属于登录日志汇总，不代表已确认的安全事件。该统计衡量已登录业务页面的访问，不是公网匿名网站的流量统计。

验证：注入 `.env` 后运行 `mvn verify -Pintegration -Dit.test=DashboardIT,MonitorIT`，覆盖事件重试、跨会话 UV 去重、租户隔离、本人范围、日期补零和权限；真实浏览器联调验证刷新新增 PV、查询控件不计数、周期切换与移动布局。

## 业务管理：订单与产品

侧边栏“业务管理”下依次展示“订单管理”(`/business/orders`)、“产品管理”(`/business/products`)，旧 `/business/projects` 重定向到订单页面。新增模块位于 `com.star.business`，按 controller、service、dto 分层，并复用认证、权限、统一异常及 AOP 操作日志。页面采用现有统计卡片、查询、表格、分页与弹窗样式。

已有库依次执行 `src/main/resources/db/business-schema.sql`、`business-data.sql`；两份均可重复执行，不清空已有业务数据，也不插入演示产品/订单。前者创建 `biz_product`、`biz_order`、`biz_order_item`，后者初始化 star 租户菜单及内置管理员授权。新空库初始化已纳入 application.yml，日常启动保持 `DB_INIT_MODE=never`。普通角色需管理员显式分配对应菜单及按钮权限，现有授权不会被重置。

产品支持名称/编码搜索、上下架状态与分类组合筛选、分页、详情、新增、编辑、上下架及删除。产品编码租户内唯一，不区分大小写，保存为大写。创建接口先保存为下架；页面对有上架权限的用户提供明确的“保存后状态”选项，默认选中上架，按钮显示“保存并上架”，依次调用创建和上架接口。选择下架或没有上架权限时只保存下架；上架请求失败时保留已创建的下架产品并提示在列表重试，避免重复新增。订单只列出上架且可售库存大于零的产品；列表加载失败、无匹配和无可售产品分别提示，支持手动刷新，从其他标签页返回时也会刷新。产品目录为租户共享资源，拥有产品查询权限的用户可以查看本租户目录，维护操作需要各自按钮权限。可售库存是当前可继续下单的数量，人工调整库存填写调整后的可售数量；版本检查防止覆盖订单扣减、归还或他人修改的结果。已有订单引用的产品不允许删除，可通过下架停止新增订单，历史快照保留。

订单支持订单号/客户/电话搜索、状态筛选、分页、详情、创建、待付款信息编辑、人工确认收款、录入物流发货、完成与取消。仅待付款允许取消，已收款订单的退款不在当前流程内；确认收款是经办人员核对款项后的业务记录，不接入支付网关，不发起实际扣款。状态严格依次为 `待付款 → 待发货 → 已发货 → 已完成`，另有 `待付款 → 已取消`，不提供直接任意改状态的接口。订单保留历史，不提供直接删除入口。

创建订单只接受产品 ID 和数量，服务端锁定上架产品并按当前价格计算金额、扣减库存，产品名称/编码/单价写入订单明细快照，后续改价不影响历史订单。金额用 BigDecimal 计算并以十进制字符串返回，前端预估用整数分计算。创建最多 50 种产品，每种 1–10000 件，禁止重复明细；多个产品按 ID 固定顺序加行锁防止超卖。创建失败整体回滚，取消订单在同一事务中归还库存且不能重复取消。提交携带 UUID 幂等编号，相同用户相同编号的相同请求只创建一笔订单；不同请求复用该编号会被拒绝。

所有查询和写操作强制当前租户。订单按当前动作对应角色的数据范围过滤，支持全部、本部门、本部门及下级、自定义部门和仅本人；部门范围使用下单时保存的部门。跨租户或超出范围的订单统一返回不存在，按钮隐藏不能代替服务端权限校验。下单产品选项单独要求下单权限，只返回上架且有库存的产品必要字段，无需额外获得产品维护权限。写操作进入产品管理/订单管理审计，收款、发货、完成和取消记录为各自中文动作，不记录客户输入正文。

API 前缀为 `/api/v1/business`：

- `GET /products`：page、size（最多 100）、keyword、status、category；返回分页、相同筛选下统计和租户分类选项。
- `GET /products/{id}`、`POST /products`、`PATCH /products/{id}`、`PATCH /products/{id}/status`、`DELETE /products/{id}`：对应 `business:product:read/create/update/status/delete`；列表权限为 `business:product:list`。更新、上下架和删除携带 version。
- `GET /products/choices?keyword=`：要求 `business:order:create`，最多 100 个匹配可售产品，可继续搜索缩小范围。
- `GET /orders`：page、size、keyword、status；`GET /orders/{id}` 查询明细，对应 `business:order:list/read`。
- `POST /orders`：`business:order:create`；`PATCH /orders/{id}`：`business:order:update`，只编辑待付款订单的客户、地址和备注。
- `POST /orders/{id}/{pay|ship|complete|cancel}`：分别要求 `business:order:pay/ship/complete/cancel`，携带 version；发货还需 trackingNo。并发版本过期或状态不符返回 409。

验证：加载 `.env`，执行 `mvn test failsafe:integration-test failsafe:verify -Pintegration -Dit.test=BusinessIT,DashboardIT,MonitorIT`。业务集成测试使用独立临时租户，检查并发超卖、库存回滚与归还、价格快照、重复提交、状态约束、权限、租户隔离及审计；不会向真实订单表插入演示数据供用户使用。


## 系统配置

系统管理下的“系统配置”（`/system/config`）按基本、登录、注册、存储四个 Tab 分组保存，配置独立于 `.env`，按租户持久化在 `sys_system_config`。基本配置支持系统名称、Logo、描述，实时更新侧栏、登录页及浏览器标题。Logo 采用 PNG/JPEG 上传并在服务端解码、重编码，限制 256 KB、1024×1024 像素，保存为图片 Data URI，登录前无需公开对象存储 Bucket 或持有临时签名 URL。

`GET /api/v1/system/config` 需要 `system:config:read`；`PUT /api/v1/system/config/{basic|login|registration}` 需要 `system:config:update` 且为内置租户管理员，每次携带 version，旧版本返回 409。修改记录在操作日志中，不记录配置正文。新库随 `schema.sql` / `data.sql` 初始化；已有库只需执行 `schema.sql` 末尾的 `sys_system_config` 建表语句，以及 `data.sql` 中菜单 210、211 和相应角色授权语句，普通启动仍不自动初始化数据库。

默认品牌为 StarAdmin，登录验证码关闭，注册申请开启。可分别开启登录、注册图片验证码，长度 4–6 位，有效期 60–300 秒。验证码绑定租户、用途与页面 Cookie，Redis 只保存答案摘要，错误尝试也消费验证码；每次提交后需换新，验证码不区分大小写。登录尝试保留每分钟 10 次限制，验证码签发每个客户端地址每分钟最多 30 次。验证码设置立即生效，不能通过省略前端字段绕过。注册关闭后隐藏入口且服务端拒绝提交，已有账号登录、待审核申请和找回密码保持原流程。

公开接口 `GET /api/v1/auth/public-config?tenantCode=` 仅返回品牌和上述认证开关白名单；`POST /api/v1/auth/captcha` 需合法 CSRF，接收 tenantCode 与 purpose（login/register），只返回图片、随机挑战 ID 和有效期，不返回答案。响应不缓存，不公开数据库、Redis 或对象存储的服务器配置和密钥。未配置租户读取默认值，不自动写入配置行。

验证：加载 `.env` 后执行 `mvn test-compile failsafe:integration-test failsafe:verify -Pintegration -Dit.test=SystemConfigIT,AccountRequestsIT`。测试使用临时租户，覆盖分组保存、版本冲突、品牌图片校验、公开字段白名单、租户隔离、管理员限制、验证码重放/用途/页面绑定和注册开关。


动态配置联调：`StorageConfigIT` 使用临时租户与受控对象存储，验证密文保存、接口脱敏、实时切换、位置保留、密钥轮换、空白保留、禁止未配置上传、版本冲突及租户隔离。原有 `SystemConfigIT` 验证品牌、登录验证码和注册开关的实时服务端校验。浏览器同租户窗口通过无配置内容的更新通知重新读取公开配置，重新激活窗口也刷新品牌及开关；不会广播或公开存储凭据。
