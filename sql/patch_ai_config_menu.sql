-- 独立「AI配置」模块菜单（可重复执行）
-- 统一维护大模型凭证；超时/侧车地址在页面只读展示（仍来自 yml）。

START TRANSACTION;

INSERT IGNORE INTO sys_menu (
    menu_id, menu_name, parent_id, order_num, path, component, query, route_name,
    is_frame, is_cache, menu_type, visible, status, perms, icon,
    create_by, create_time, update_by, update_time, remark
) VALUES
    (2600, 'AI配置', 2000, 6, 'ai', 'business/ai/index', '', 'AiConfig', 1, 0, 'C', '0', '0', 'business:ai:config:query', 'edit', 'admin', NOW(), '', NULL, '系统统一大模型凭证与作用范围'),
    (2610, 'AI配置查询', 2600, 1, '', '', '', '', 1, 0, 'F', '0', '0', 'business:ai:config:query', '#', 'admin', NOW(), '', NULL, ''),
    (2611, 'AI配置修改', 2600, 2, '', '', '', '', 1, 0, 'F', '0', '0', 'business:ai:config:edit', '#', 'admin', NOW(), '', NULL, '');

UPDATE sys_menu
SET menu_name = 'AI配置',
    parent_id = 2000,
    order_num = 6,
    path = 'ai',
    component = 'business/ai/index',
    route_name = 'AiConfig',
    is_frame = 1,
    is_cache = 0,
    menu_type = 'C',
    visible = '0',
    status = '0',
    perms = 'business:ai:config:query',
    icon = 'edit',
    update_by = 'admin',
    update_time = NOW(),
    remark = '系统统一大模型凭证与作用范围'
WHERE menu_id = 2600;

UPDATE sys_menu SET menu_name = 'AI配置查询', parent_id = 2600, order_num = 1, perms = 'business:ai:config:query', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2610;
UPDATE sys_menu SET menu_name = 'AI配置修改', parent_id = 2600, order_num = 2, perms = 'business:ai:config:edit', menu_type = 'F', visible = '0', status = '0', update_by = 'admin', update_time = NOW() WHERE menu_id = 2611;

-- 拥有知识库编辑权限或业务模块权限的角色补齐 AI 配置
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2600 FROM sys_role_menu WHERE menu_id IN (2000, 2400, 2412);
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2610 FROM sys_role_menu WHERE menu_id = 2600;
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 2611 FROM sys_role_menu WHERE menu_id IN (2600, 2412);

-- 超级管理员角色（role_id=1）确保可见
INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (1, 2600), (1, 2610), (1, 2611);

COMMIT;
