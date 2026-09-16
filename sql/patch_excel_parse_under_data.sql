-- 将「市场分析 > 车载分析 > 车载市场分析」改为「数据接入与解析 > Excel解析」
-- 可重复执行。

START TRANSACTION;

-- 主菜单：挪到数据接入与解析，改名为 Excel解析
UPDATE sys_menu
SET menu_name = 'Excel解析',
    parent_id = 2100,
    order_num = 1,
    path = 'excel',
    component = 'business/data/excel/index',
    query = '',
    route_name = '',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'business:data:excel:list',
    icon = 'excel',
    update_by = 'admin',
    update_time = NOW(),
    remark = 'Excel/CSV 解析、字段识别、预览和质量检查'
WHERE menu_id = 2101;

-- 按钮权限改名
UPDATE sys_menu SET menu_name = 'Excel解析查询', parent_id = 2101, update_by = 'admin', update_time = NOW() WHERE menu_id = 2110;
UPDATE sys_menu SET menu_name = 'Excel解析新增', parent_id = 2101, update_by = 'admin', update_time = NOW() WHERE menu_id = 2111;
UPDATE sys_menu SET menu_name = 'Excel解析修改', parent_id = 2101, update_by = 'admin', update_time = NOW() WHERE menu_id = 2112;
UPDATE sys_menu SET menu_name = 'Excel解析删除', parent_id = 2101, update_by = 'admin', update_time = NOW() WHERE menu_id = 2113;
UPDATE sys_menu SET menu_name = 'Excel解析导出', parent_id = 2101, update_by = 'admin', update_time = NOW() WHERE menu_id = 2114;

-- PDF / 文本顺序顺延
UPDATE sys_menu SET order_num = 2, update_by = 'admin', update_time = NOW() WHERE menu_id = 2102;
UPDATE sys_menu SET order_num = 3, update_by = 'admin', update_time = NOW() WHERE menu_id = 2103;

-- 隐藏市场分析下空的「车载分析」目录（若仍挂有其他子菜单则不处理）
UPDATE sys_menu
SET visible = '1',
    status = '1',
    update_by = 'admin',
    update_time = NOW(),
    remark = '已迁移为「数据接入与解析 > Excel解析」，目录停用'
WHERE menu_id = 2202
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT menu_id FROM sys_menu WHERE parent_id = 2202 AND status = '0') t
  );

-- 若存在旧的「车载市场分析」独立入口(2204)，一并停用，避免与 Excel解析 重复
UPDATE sys_menu
SET visible = '1',
    status = '1',
    update_by = 'admin',
    update_time = NOW(),
    remark = '已由「数据接入与解析 > Excel解析」替代'
WHERE menu_id = 2204;

-- 拥有 Excel解析 的角色补齐父目录
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2100 FROM sys_role_menu WHERE menu_id IN (2101, 2110, 2111, 2112, 2113, 2114);

COMMIT;
