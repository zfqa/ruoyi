-- 将Excel数据接入与解析迁入“市场分析 / 车载分析”。
-- PDF解析和文本结构化继续保留在原“数据接入与解析”目录。
-- 可重复执行；功能权限标识保持不变。
START TRANSACTION;

-- 撤回曾创建的整车概览中间菜单，并完整恢复整车市场分析。
UPDATE sys_menu
SET menu_name = '整车市场分析', parent_id = 2200, order_num = 1, path = 'vehicle',
    component = 'business/analysis/vehicle/index', query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'business:analysis:vehicle:list', icon = 'chart', remark = '整车市场分析菜单'
WHERE menu_id = 2201;

UPDATE sys_menu SET parent_id = 2201 WHERE menu_id IN (2210, 2211, 2212, 2213, 2214);
DELETE FROM sys_role_menu WHERE menu_id = 2203;
DELETE FROM sys_menu WHERE menu_id = 2203;

-- 原车载显示分析页面保留为车载分析目录下的概览页。
INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (2204, '车载分析概览', 2202, 1, 'overview', 'business/analysis/display/index', '', '',
     1, 0, 'C', '0', '0', 'business:analysis:display:list', 'chart',
     'admin', NOW(), '', NULL, '车载显示分析数据概览')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), menu_type = VALUES(menu_type),
    visible = VALUES(visible), status = VALUES(status), perms = VALUES(perms), icon = VALUES(icon),
    remark = VALUES(remark);

UPDATE sys_menu
SET menu_name = '车载分析', parent_id = 2200, order_num = 2, path = 'display',
    component = NULL, query = '', route_name = '', is_frame = 1, is_cache = 0,
    menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'monitor',
    remark = '车载分析目录'
WHERE menu_id = 2202;

UPDATE sys_menu
SET parent_id = 2000, order_num = 1, path = 'data', menu_type = 'M',
    component = NULL, perms = '', remark = 'PDF解析与文本结构化目录'
WHERE menu_id = 2100;

UPDATE sys_menu
SET parent_id = 2202, order_num = 2, path = 'excel',
    component = 'business/data/excel/index', menu_type = 'C',
    perms = 'business:data:excel:list', remark = '车载分析Excel数据接入与解析菜单'
WHERE menu_id = 2101;

UPDATE sys_menu SET parent_id = 2100 WHERE menu_id IN (2102, 2103);

UPDATE sys_menu SET parent_id = 2204 WHERE menu_id IN (2220, 2221, 2222, 2223, 2224);

-- 已拥有原车载显示分析菜单的角色自动获得新概览页。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 2204 FROM sys_role_menu WHERE menu_id = 2202;

COMMIT;
