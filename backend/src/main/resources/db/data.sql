-- 系统基础数据：初始化租户、内置管理员、部门及系统菜单权限。
-- 关联模块：先执行 schema.sql；前端导航与 PermissionService 读取这些菜单，日志菜单在 log-data.sql 初始化。

-- 基础数据：直接在已建好的目标库中执行本文件，不依赖 Java 或会话变量。
-- 开发登录：租户 star，账号 admin，初始密码 StarAdmin@2026；建议在个人中心修改。
-- 只初始化系统必需数据。重复执行保留已有记录和密码。

INSERT INTO sys_tenant(id,code,name) VALUES (1,'star','Star 科技有限公司')
ON DUPLICATE KEY UPDATE id = sys_tenant.id;
INSERT INTO sys_dept(id,tenant_id,name,sort) VALUES (1,1,'总经办',0)
ON DUPLICATE KEY UPDATE id = sys_dept.id;
INSERT INTO sys_role(id,tenant_id,name,code,scope,builtin)
VALUES (1,1,'超级管理员','tenant_admin','全部数据',TRUE)
ON DUPLICATE KEY UPDATE id = sys_role.id;

-- 页面导航与按钮操作权限，供后端权限校验及前端菜单展示共同使用。
INSERT INTO sys_menu(id,tenant_id,name,type,path,permission,sort,icon)
VALUES (90,1,'首页','菜单','/dashboard','dashboard:read',0,'home')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,type,path,sort,icon)
VALUES  (100,1,'系统管理','目录','/system',1,'settings')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES  (110,1,'用户管理',100,'菜单','/system/users','system:user:list',0,'users')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (111,1,'查看详情',110,'按钮','system:user:read',0)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (112,1,'新增',110,'按钮','system:user:create',1)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (113,1,'编辑',110,'按钮','system:user:update',2)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (114,1,'删除',110,'按钮','system:user:delete',3)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (115,1,'导出',110,'按钮','system:user:export',4)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (116,1,'授权角色',110,'按钮','system:user:assign-role',5)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (117,1,'重置密码',110,'按钮','system:user:reset-password',6)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES  (130,1,'角色管理',100,'菜单','/system/roles','system:role:list',1,'shield')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (131,1,'查看详情',130,'按钮','system:role:read',0)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (132,1,'新增',130,'按钮','system:role:create',1)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (133,1,'编辑',130,'按钮','system:role:update',2)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (134,1,'删除',130,'按钮','system:role:delete',3)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (135,1,'导出',130,'按钮','system:role:export',4)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (136,1,'分配权限',130,'按钮','system:role:grant',5)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES  (150,1,'菜单管理',100,'菜单','/system/menus','system:menu:list',2,'list-tree')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (151,1,'查看详情',150,'按钮','system:menu:read',0)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (152,1,'新增',150,'按钮','system:menu:create',1)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (153,1,'编辑',150,'按钮','system:menu:update',2)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (154,1,'删除',150,'按钮','system:menu:delete',3)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (155,1,'导出',150,'按钮','system:menu:export',4)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES  (170,1,'部门管理',100,'菜单','/system/departments','system:dept:list',3,'network')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (171,1,'查看详情',170,'按钮','system:dept:read',0)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (172,1,'新增',170,'按钮','system:dept:create',1)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (173,1,'编辑',170,'按钮','system:dept:update',2)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (174,1,'删除',170,'按钮','system:dept:delete',3)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (175,1,'导出',170,'按钮','system:dept:export',4)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES  (190,1,'岗位管理',100,'菜单','/system/posts','system:post:list',4,'briefcase')
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (191,1,'查看详情',190,'按钮','system:post:read',0)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (192,1,'新增',190,'按钮','system:post:create',1)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (193,1,'编辑',190,'按钮','system:post:update',2)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (194,1,'删除',190,'按钮','system:post:delete',3)
ON DUPLICATE KEY UPDATE id = sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES  (195,1,'导出',190,'按钮','system:post:export',4)
ON DUPLICATE KEY UPDATE id = sys_menu.id;

-- 系统配置菜单与保存权限；合并在系统基础数据中，重复执行不重置授权。
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
VALUES (210,1,'系统配置',100,'菜单','/system/config','system:config:read',5,'settings')
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
VALUES (211,1,'保存配置',210,'按钮','system:config:update',0)
ON DUPLICATE KEY UPDATE id=sys_menu.id;

INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT 1,1,id FROM sys_menu WHERE tenant_id=1
ON DUPLICATE KEY UPDATE tenant_id = sys_role_menu.tenant_id;
INSERT INTO sys_user(id,tenant_id,name,username,password_hash,dept_id,must_change_password,builtin)
VALUES  (1001,1,'管理员','admin','$2a$12$r6vENZrugMijJTea2i8pEOL610Cn6W7Y23axUdLdgx6rn4nKfpDbG',1,TRUE,TRUE)
ON DUPLICATE KEY UPDATE id = sys_user.id;
INSERT INTO sys_user_role(tenant_id,user_id,role_id)
VALUES  (1,1001,1)
ON DUPLICATE KEY UPDATE tenant_id = sys_user_role.tenant_id;
