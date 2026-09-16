-- 仅恢复「市场分析 > 车载分析 > 车载市场分析」原入口，不改动 Excel解析/整车/PDF/文本等其它菜单。
-- 页面：business/analysis/display/index（OnboardMarket）
-- 可重复执行。

START TRANSACTION;

-- 恢复车载分析目录
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
    (2202, '车载分析', 2200, 2, 'display', NULL, '', '', 1, 0, 'M', '0', '0', '', 'monitor', 'admin', NOW(), '', NULL, '车载分析目录');

UPDATE sys_menu
SET menu_name = '车载分析',
    parent_id = 2200,
    order_num = 2,
    path = 'display',
    component = NULL,
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'M',
    visible = '0',
    status = '0',
    perms = '',
    icon = 'monitor',
    update_by = 'admin',
    update_time = NOW(),
    remark = '车载分析目录'
WHERE menu_id = 2202;

-- 恢复车载市场分析页面入口（组长版完整车载解析）
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
    (2204, '车载市场分析', 2202, 1, 'onboard', 'business/analysis/display/index', '', 'OnboardMarket', 1, 0, 'C', '0', '0', 'business:data:excel:list', 'monitor', 'admin', NOW(), '', NULL, '组长版完整车载市场解析与报告入口');

UPDATE sys_menu
SET menu_name = '车载市场分析',
    parent_id = 2202,
    order_num = 1,
    path = 'onboard',
    component = 'business/analysis/display/index',
    query = '',
    route_name = 'OnboardMarket',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'business:data:excel:list',
    icon = 'monitor',
    update_by = 'admin',
    update_time = NOW(),
    remark = '组长版完整车载市场解析与报告入口'
WHERE menu_id = 2204;

-- 按钮权限（复用既有 excel/report 接口权限字，不改其它模块权限定义）
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
    (2230, '车载市场分析查询', 2204, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:query', '#', 'admin', NOW(), '', NULL, ''),
    (2231, '车载市场分析新增', 2204, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:add', '#', 'admin', NOW(), '', NULL, ''),
    (2232, '车载市场分析修改', 2204, 3, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:edit', '#', 'admin', NOW(), '', NULL, ''),
    (2233, '车载市场分析删除', 2204, 4, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:remove', '#', 'admin', NOW(), '', NULL, ''),
    (2234, '车载市场分析导出', 2204, 5, '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:export', '#', 'admin', NOW(), '', NULL, ''),
    (2235, '车载报告查询', 2204, 6, '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:query', '#', 'admin', NOW(), '', NULL, ''),
    (2236, '车载报告导出', 2204, 7, '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:export', '#', 'admin', NOW(), '', NULL, '');

UPDATE sys_menu SET menu_name = '车载市场分析查询', parent_id = 2204, order_num = 1, perms = 'business:data:excel:query', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2230;
UPDATE sys_menu SET menu_name = '车载市场分析新增', parent_id = 2204, order_num = 2, perms = 'business:data:excel:add', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2231;
UPDATE sys_menu SET menu_name = '车载市场分析修改', parent_id = 2204, order_num = 3, perms = 'business:data:excel:edit', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2232;
UPDATE sys_menu SET menu_name = '车载市场分析删除', parent_id = 2204, order_num = 4, perms = 'business:data:excel:remove', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2233;
UPDATE sys_menu SET menu_name = '车载市场分析导出', parent_id = 2204, order_num = 5, perms = 'business:data:excel:export', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2234;
UPDATE sys_menu SET menu_name = '车载报告查询', parent_id = 2204, order_num = 6, perms = 'business:report:query', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2235;
UPDATE sys_menu SET menu_name = '车载报告导出', parent_id = 2204, order_num = 7, perms = 'business:report:export', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2236;

-- 仅给已有市场分析权限的角色补挂车载菜单，不扩大其它业务权限
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2202 FROM sys_role_menu WHERE menu_id IN (2200, 2201, 2203);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2204 FROM sys_role_menu WHERE menu_id IN (2200, 2201, 2202, 2203);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2230 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2231 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2232 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2233 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2234 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2235 FROM sys_role_menu WHERE menu_id = 2204;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2236 FROM sys_role_menu WHERE menu_id = 2204;

COMMIT;
