-- 系统监控菜单与权限；仅新增 star 租户的导航及内置管理员授权，可重复执行。
-- 在线状态保存于 Redis，采样历史保存在应用内存；无需新增业务表。
START TRANSACTION;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 500,id,'系统监控',NULL,'目录','/monitor','',6,'monitor' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 510,id,'服务监控',500,'菜单','/monitor/overview','monitor:server:read',0,'activity' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 520,id,'在线用户',500,'菜单','/monitor/sessions','monitor:session:list',1,'users' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 521,id,'强退会话',520,'按钮','','monitor:session:kick',0,'log-out' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 530,id,'缓存监控',500,'菜单','/monitor/cache','monitor:cache:read',2,'database' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT r.tenant_id,r.id,m.id FROM sys_role r
JOIN sys_tenant t ON t.id=r.tenant_id AND t.code='star'
JOIN sys_menu m ON m.tenant_id=r.tenant_id AND m.id IN(500,510,520,521,530)
WHERE r.builtin=TRUE
ON DUPLICATE KEY UPDATE tenant_id=sys_role_menu.tenant_id;
COMMIT;
