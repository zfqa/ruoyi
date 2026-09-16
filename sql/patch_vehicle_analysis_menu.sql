-- 将现有菜单迁移为“通用 Excel 导入”和“车载市场分析”两个独立入口。
-- 可重复执行；不删除旧车载 CRUD 代码和数据表，只取消其菜单及按钮入口。

START TRANSACTION;

-- 迁移前拥有原 2101 车载入口的角色继续获得新的车载市场分析菜单。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2204
FROM sys_role_menu
WHERE menu_id = 2101;

-- 旧简易车载 CRUD 的菜单按钮不再对外展示。
DELETE FROM sys_role_menu WHERE menu_id IN (2220, 2221, 2222, 2223, 2224);
DELETE FROM sys_menu WHERE menu_id IN (2220, 2221, 2222, 2223, 2224);

-- 确保“整车分析”“车载分析”和“车载市场分析”菜单存在。
INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
    (2203, '整车分析', 2200, 1, 'vehicle', NULL, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', NOW(), '', NULL, '整车分析目录'),
    (2202, '车载分析', 2200, 2, 'display', NULL, '', '', 1, 0, 'M', '0', '0', '', 'monitor', 'admin', NOW(), '', NULL, '车载分析目录'),
    (2204, '车载市场分析', 2202, 1, 'onboard', 'business/analysis/display/index', '', 'OnboardMarket', 1, 0, 'C', '0', '0', 'business:data:excel:list', 'monitor', 'admin', NOW(), '', NULL, '组长版完整车载市场解析与报告入口');

UPDATE sys_menu
SET menu_name = '整车分析', parent_id = 2200, order_num = 1,
    path = 'vehicle', component = NULL, query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'dashboard', update_by = 'admin', update_time = NOW(), remark = '整车分析目录'
WHERE menu_id = 2203;

UPDATE sys_menu
SET menu_name = '整车市场分析', parent_id = 2203, order_num = 1,
    path = 'market', component = 'business/analysis/vehicle/index', query = '', route_name = 'VehicleMarket',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'business:analysis:vehicle:list', icon = 'chart', update_by = 'admin', update_time = NOW(), remark = '整车市场分析菜单（支持页签缓存）'
WHERE menu_id = 2201;

UPDATE sys_menu
SET menu_name = '车载分析', parent_id = 2200, order_num = 2,
    path = 'display', component = NULL, query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'M', visible = '0', status = '0',
    perms = '', icon = 'monitor', update_by = 'admin', update_time = NOW(), remark = '车载分析目录'
WHERE menu_id = 2202;

UPDATE sys_menu
SET menu_name = '车载市场分析', parent_id = 2202, order_num = 1,
    path = 'onboard', component = 'business/analysis/display/index', query = '', route_name = 'OnboardMarket',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'business:data:excel:list', icon = 'monitor', update_by = 'admin', update_time = NOW(),
    remark = '组长版完整车载市场解析与报告入口'
WHERE menu_id = 2204;

-- 通用 Excel 导入回到“数据接入与解析”目录，保留增强后的 market-import.vue。
UPDATE sys_menu
SET menu_name = 'Excel导入', parent_id = 2100, order_num = 1,
    path = 'excel', component = 'business/data/excel/index', query = '', route_name = '',
    is_frame = 1, is_cache = 0, menu_type = 'C', visible = '0', status = '0',
    perms = 'business:data:excel:list', icon = 'excel', update_by = 'admin', update_time = NOW(),
    remark = '通用Excel与CSV解析、字段识别、预览和质量检查'
WHERE menu_id = 2101;

UPDATE sys_menu SET menu_name = 'Excel导入查询', update_by = 'admin', update_time = NOW() WHERE menu_id = 2110;
UPDATE sys_menu SET menu_name = 'Excel导入新增', update_by = 'admin', update_time = NOW() WHERE menu_id = 2111;
UPDATE sys_menu SET menu_name = 'Excel导入修改', update_by = 'admin', update_time = NOW() WHERE menu_id = 2112;
UPDATE sys_menu SET menu_name = 'Excel导入删除', update_by = 'admin', update_time = NOW() WHERE menu_id = 2113;
UPDATE sys_menu SET menu_name = 'Excel导入导出', update_by = 'admin', update_time = NOW() WHERE menu_id = 2114;

-- 新车载市场分析页面复用现有解析和报告接口权限。
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

UPDATE sys_menu SET menu_name = '车载市场分析查询', parent_id = 2204, order_num = 1, perms = 'business:data:excel:query', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2230;
UPDATE sys_menu SET menu_name = '车载市场分析新增', parent_id = 2204, order_num = 2, perms = 'business:data:excel:add', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2231;
UPDATE sys_menu SET menu_name = '车载市场分析修改', parent_id = 2204, order_num = 3, perms = 'business:data:excel:edit', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2232;
UPDATE sys_menu SET menu_name = '车载市场分析删除', parent_id = 2204, order_num = 4, perms = 'business:data:excel:remove', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2233;
UPDATE sys_menu SET menu_name = '车载市场分析导出', parent_id = 2204, order_num = 5, perms = 'business:data:excel:export', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2234;
UPDATE sys_menu SET menu_name = '车载报告查询', parent_id = 2204, order_num = 6, perms = 'business:report:query', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2235;
UPDATE sys_menu SET menu_name = '车载报告导出', parent_id = 2204, order_num = 7, perms = 'business:report:export', menu_type = 'F', update_by = 'admin', update_time = NOW() WHERE menu_id = 2236;

-- 为已有角色补齐必要的父目录；不自动扩大业务按钮权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2100 FROM sys_role_menu WHERE menu_id IN (2101, 2110, 2111, 2112, 2113, 2114);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2202 FROM sys_role_menu WHERE menu_id IN (2204, 2230, 2231, 2232, 2233, 2234, 2235, 2236);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2203 FROM sys_role_menu WHERE menu_id IN (2201, 2210, 2211, 2212, 2213, 2214);

COMMIT;
