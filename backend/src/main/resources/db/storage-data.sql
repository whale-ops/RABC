-- 文件管理菜单初始化与旧版升级；关联 PermissionService、侧边栏及 StorageFileController。
-- 统一维护“文件管理 -> 图片管理 / 视频管理 / 附件管理”，仅处理 star 租户，可重复执行。
-- 内置管理员初始化全部文件权限；普通角色只继承原有文件入口，不增加上传或删除权限。
-- 不清空文件索引，不修改 OSS / MinIO 对象，也不重置菜单的启停状态。
START TRANSACTION;
INSERT INTO sys_menu(id,tenant_id,name,type,path,permission,sort,icon)
SELECT 405,id,'文件管理','目录','/files','',5,'folder' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 400,id,'图片管理',405,'菜单','/images','storage:file:list',0,'image' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort,icon)
SELECT 401,id,'上传文件',400,'按钮','storage:file:upload',0,'upload' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort,icon)
SELECT 402,id,'下载文件',400,'按钮','storage:file:download',1,'download' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort,icon)
SELECT 403,id,'删除文件',400,'按钮','storage:file:delete',2,'trash' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 404,id,'附件管理',405,'菜单','/attachments','storage:file:list',2,'paperclip' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 406,id,'视频管理',405,'菜单','/videos','storage:file:list',1,'video' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;

-- 兼容旧版单文件入口及尚未分组的分类入口；仅在需要调整时递增菜单版本。
UPDATE sys_menu m JOIN sys_tenant t ON t.id=m.tenant_id AND t.code='star'
SET m.name='图片管理',m.path='/images',m.icon='image',m.version=m.version+1
WHERE m.id=400 AND m.path='/files';
UPDATE sys_menu m JOIN sys_tenant t ON t.id=m.tenant_id AND t.code='star'
SET m.parent_id=405,m.sort=0,m.version=m.version+1
WHERE m.id=400 AND m.path='/images' AND (m.parent_id IS NULL OR m.parent_id<>405 OR m.sort<>0);
UPDATE sys_menu m JOIN sys_tenant t ON t.id=m.tenant_id AND t.code='star'
SET m.parent_id=405,m.sort=2,m.version=m.version+1
WHERE m.id=404 AND m.path='/attachments' AND (m.parent_id IS NULL OR m.parent_id<>405 OR m.sort<>2);

-- 新空库为内置管理员初始化七条目录、菜单及按钮关系。
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT r.tenant_id,r.id,m.id FROM sys_role r
JOIN sys_tenant t ON t.id=r.tenant_id AND t.code='star'
JOIN sys_menu m ON m.tenant_id=r.tenant_id AND m.id IN(400,401,402,403,404,405,406)
WHERE r.builtin=TRUE
ON DUPLICATE KEY UPDATE tenant_id=sys_role_menu.tenant_id;

-- 已有文件角色继承视频入口；仍沿用具体的上传、下载和删除权限。
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT DISTINCT rm.tenant_id,rm.role_id,406 FROM sys_role_menu rm
JOIN sys_tenant t ON t.id=rm.tenant_id AND t.code='star'
WHERE rm.menu_id IN(400,404)
ON DUPLICATE KEY UPDATE menu_id=sys_role_menu.menu_id;

-- 已有角色继承图片 / 附件入口和父目录；保留原来的具体读写权限。
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT rm.tenant_id,rm.role_id,404 FROM sys_role_menu rm
JOIN sys_tenant t ON t.id=rm.tenant_id AND t.code='star'
WHERE rm.menu_id=400
ON DUPLICATE KEY UPDATE menu_id=sys_role_menu.menu_id;
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT DISTINCT rm.tenant_id,rm.role_id,405 FROM sys_role_menu rm
JOIN sys_tenant t ON t.id=rm.tenant_id AND t.code='star'
WHERE rm.menu_id IN(400,404)
ON DUPLICATE KEY UPDATE menu_id=sys_role_menu.menu_id;
COMMIT;
