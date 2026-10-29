-- 业务菜单与按钮权限，仅初始化 star 租户的内置管理员；保留其他角色已有授权与启停状态。
START TRANSACTION;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 600,id,'业务管理',NULL,'目录','/business','',2,'briefcase' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 610,id,'订单管理',600,'菜单','/business/orders','business:order:list',0,'list' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,path,permission,sort,icon)
SELECT 630,id,'产品管理',600,'菜单','/business/products','business:product:list',1,'package' FROM sys_tenant WHERE code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_menu(id,tenant_id,name,parent_id,type,permission,sort)
SELECT a.id,t.id,a.name,a.parent,'按钮',a.permission,a.sort FROM sys_tenant t JOIN (
 SELECT 611 id,'查看详情' name,610 parent,'business:order:read' permission,0 sort
 UNION ALL SELECT 612,'创建订单',610,'business:order:create',1
 UNION ALL SELECT 613,'编辑订单',610,'business:order:update',2
 UNION ALL SELECT 614,'取消订单',610,'business:order:cancel',3
 UNION ALL SELECT 615,'确认收款',610,'business:order:pay',4
 UNION ALL SELECT 616,'发货',610,'business:order:ship',5
 UNION ALL SELECT 617,'完成订单',610,'business:order:complete',6
 UNION ALL SELECT 631,'查看详情',630,'business:product:read',0
 UNION ALL SELECT 632,'新增产品',630,'business:product:create',1
 UNION ALL SELECT 633,'编辑产品',630,'business:product:update',2
 UNION ALL SELECT 634,'上下架',630,'business:product:status',3
 UNION ALL SELECT 635,'删除产品',630,'business:product:delete',4
) a WHERE t.code='star'
ON DUPLICATE KEY UPDATE id=sys_menu.id;
INSERT INTO sys_role_menu(tenant_id,role_id,menu_id)
SELECT r.tenant_id,r.id,m.id FROM sys_role r
JOIN sys_tenant t ON t.id=r.tenant_id AND t.code='star'
JOIN sys_menu m ON m.tenant_id=r.tenant_id AND m.id IN(600,610,611,612,613,614,615,616,617,630,631,632,633,634,635)
WHERE r.builtin=TRUE
ON DUPLICATE KEY UPDATE tenant_id=sys_role_menu.tenant_id;
COMMIT;
