-- 日志权限初始化：为 star 租户增加日志导航、详情与导出权限。
-- 关联模块：PermissionService 和前端侧边栏使用这些菜单；依赖基础数据和日志建表脚本。

-- 日志模块菜单与权限。先执行 data.sql 和 log-schema.sql；仅初始化 star 租户。
-- 重复执行保留现有菜单与授权，不为普通角色自动授予日志权限。
START TRANSACTION;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 300,id,'日志管理',NULL,'目录','/audit','',4,'file-text' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 310,id,'操作日志',300,'菜单','/audit/operations','audit:operation:list',0,'file-text' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 311,id,'查看详情',310,'按钮','','audit:operation:read',0,'file-text' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 312,id,'导出',310,'按钮','','audit:operation:export',1,'download' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 320,id,'登录日志',300,'菜单','/audit/logins','audit:login:list',1,'log-in' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 321,id,'查看详情',320,'按钮','','audit:login:read',0,'file-text' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 322,id,'导出',320,'按钮','','audit:login:export',1,'download' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT r.tenant_id,r.id,m.id FROM sys_role r
JOIN sys_tenant t ON t.id=r.tenant_id AND t.code='star'
JOIN sys_menu m ON m.tenant_id=r.tenant_id AND m.id IN(300,310,311,312,320,321,322)
WHERE r.builtin=TRUE
ON DUPLICATE KEY UPDATE tenant_id=sys_role_menu.tenant_id;
COMMIT;
