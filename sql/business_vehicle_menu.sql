-- 懂车帝车辆采集菜单挂到「新闻中心」；幂等插入，可对已有库重复执行。

-- 菜单：新闻中心 > 懂车帝车辆采集
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT
  '懂车帝车辆采集', menu_id, 2, 'vehicle', 'business/vehicle/index', '', '', 1, 0,
  'C', '0', '0', 'business:vehicle:collect:list', 'car', 'admin', sysdate(), '懂车帝车型参数采集（入库统一知识库）'
FROM sys_menu
WHERE menu_id = 2300
  AND NOT EXISTS (
    SELECT 1 FROM sys_menu existing_menu
    WHERE existing_menu.component = 'business/vehicle/index'
  );

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车辆采集查询', menu_id, 1, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:collect:query', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:collect:query');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车辆采集新增', menu_id, 2, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:collect:add', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:collect:add');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车辆采集修改', menu_id, 3, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:collect:edit', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:collect:edit');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车辆采集删除', menu_id, 4, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:collect:remove', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:collect:remove');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车辆采集导出', menu_id, 5, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:collect:export', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:collect:export');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车型数据列表', menu_id, 6, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:model:list', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:model:list');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '车型数据查询', menu_id, 7, '', '', '', '', 1, 0,
       'F', '0', '0', 'business:vehicle:model:query', '#', 'admin', sysdate(), ''
FROM sys_menu
WHERE component = 'business/vehicle/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'business:vehicle:model:query');
