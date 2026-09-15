-- 在“市场分析”下拆分“整车分析”和“车载分析”两个目录。
-- PDF解析和文本结构化继续保留在原“数据接入与解析”目录。
-- 可重复执行；不恢复已经确认删除的“车载分析概览”空壳页面。
START TRANSACTION;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (2203, '整车分析', 2200, 1, 'vehicle', NULL, '', '',
     1, 0, 'M', '0', '0', '', 'dashboard',
     'admin', NOW(), '', NULL, '整车分析目录')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), query = VALUES(query), route_name = VALUES(route_name),
    is_frame = VALUES(is_frame), is_cache = VALUES(is_cache), menu_type = VALUES(menu_type),
    visible = VALUES(visible), status = VALUES(status), perms = VALUES(perms), icon = VALUES(icon),
    remark = VALUES(remark);

UPDATE sys_menu
SET menu_name = '整车市场分析', parent_id = 2203, order_num = 1, path = 'market',
    component = 'business/analysis/vehicle/index', query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'business:analysis:vehicle:list', icon = 'chart', remark = '整车市场分析菜单'
WHERE menu_id = 2201;

UPDATE sys_menu
SET menu_name = '车载分析', parent_id = 2200, order_num = 2, path = 'display',
    component = NULL, query = '', route_name = '', is_frame = 1, is_cache = 0,
    menu_type = 'M', visible = '0', status = '0', perms = '', icon = 'monitor',
    remark = '车载分析目录'
WHERE menu_id = 2202;

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (2101, '车载市场分析', 2202, 1, 'excel', 'business/data/excel/index', '', '',
     1, 0, 'C', '0', '0', 'business:data:excel:list', 'excel',
     'admin', NOW(), '', NULL, '车载显示Excel解析、指标计算与竞争社报告菜单')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    path = VALUES(path), component = VALUES(component), query = VALUES(query), route_name = VALUES(route_name),
    is_frame = VALUES(is_frame), is_cache = VALUES(is_cache), menu_type = VALUES(menu_type),
    visible = VALUES(visible), status = VALUES(status), perms = VALUES(perms), icon = VALUES(icon),
    remark = VALUES(remark);

INSERT INTO sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
VALUES
    (2110, '车载市场分析查询', 2101, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:query', '#', 'admin', NOW(), '', NULL, ''),
    (2111, '车载市场分析新增', 2101, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:add', '#', 'admin', NOW(), '', NULL, ''),
    (2112, '车载市场分析修改', 2101, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:edit', '#', 'admin', NOW(), '', NULL, ''),
    (2113, '车载市场分析删除', 2101, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:remove', '#', 'admin', NOW(), '', NULL, ''),
    (2114, '车载市场分析导出', 2101, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:export', '#', 'admin', NOW(), '', NULL, '')
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name), parent_id = VALUES(parent_id), order_num = VALUES(order_num),
    perms = VALUES(perms), visible = VALUES(visible), status = VALUES(status);

UPDATE sys_menu SET parent_id = 2201 WHERE menu_id IN (2210, 2211, 2212, 2213, 2214);
UPDATE sys_menu SET parent_id = 2100 WHERE menu_id IN (2102, 2103);

-- 已拥有整车市场分析页面的角色自动获得新的整车分析父目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 2203 FROM sys_role_menu WHERE menu_id = 2201;

-- 已能访问整车市场分析或车载分析目录的角色，自动获得恢复后的车载市场分析页面及按钮。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2101 FROM sys_role_menu WHERE menu_id IN (2201, 2202);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, ids.menu_id
FROM sys_role_menu rm
CROSS JOIN (SELECT 2110 AS menu_id UNION ALL SELECT 2111 UNION ALL SELECT 2112 UNION ALL SELECT 2113 UNION ALL SELECT 2114) ids
WHERE rm.menu_id = 2101;

-- 保持先前确认的清理：不恢复概览空壳与新闻处理菜单。
DELETE FROM sys_role_menu WHERE menu_id IN (2204, 2220, 2221, 2222, 2223, 2224, 2320, 2321, 2322, 2323, 2324, 2302);
DELETE FROM sys_menu WHERE menu_id IN (2220, 2221, 2222, 2223, 2224, 2204, 2320, 2321, 2322, 2323, 2324, 2302);

COMMIT;
