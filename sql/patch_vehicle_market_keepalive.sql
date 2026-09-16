-- 确保「整车市场分析」开启页签缓存：离开再进入不重建页面，仅右上角刷新才清空。
-- is_cache: 0=缓存, 1=不缓存；route_name 必须与前端组件 name「VehicleMarket」一致。

UPDATE sys_menu
SET route_name = 'VehicleMarket',
    is_cache = 0,
    component = 'business/analysis/vehicle/index',
    update_by = 'admin',
    update_time = NOW(),
    remark = '整车市场分析菜单（支持页签缓存）'
WHERE menu_id = 2201
   OR (menu_name = '整车市场分析' AND menu_type = 'C');
