-- ----------------------------
-- 业务模块菜单权限初始化
-- ----------------------------

-- 一级目录：业务模块
insert into sys_menu values('2000', '业务模块', '0', '5', 'business', null, '', '', 1, 0, 'M', '0', '0', '', 'business', 'admin', sysdate(), '', null, '业务模块目录');

-- 二级目录：数据接入与解析（Excel导入、PDF解析和文本结构化）
insert into sys_menu values('2100', '数据接入与解析', '2000', '1', 'data', null, '', '', 1, 0, 'M', '0', '0', '', 'data', 'admin', sysdate(), '', null, 'PDF解析与文本结构化目录');
-- 二级目录：市场分析
insert into sys_menu values('2200', '市场分析', '2000', '2', 'analysis', null, '', '', 1, 0, 'M', '0', '0', '', 'analysis', 'admin', sysdate(), '', null, '市场分析目录');
-- 二级目录：新闻中心
insert into sys_menu values('2300', '新闻中心', '2000', '3', 'news', null, '', '', 1, 0, 'M', '0', '0', '', 'news', 'admin', sysdate(), '', null, '新闻中心目录');
-- 二级菜单：固定知识库
insert into sys_menu values('2400', '固定知识库', '2000', '4', 'knowledge', 'business/knowledge/index', '', '', 1, 0, 'C', '0', '0', 'business:knowledge:list', 'knowledge', 'admin', sysdate(), '', null, '固定知识库菜单');
-- 二级菜单：AI分析报告
insert into sys_menu values('2500', 'AI分析报告', '2000', '5', 'report', 'business/report/index', '', '', 1, 0, 'C', '0', '0', 'business:report:list', 'report', 'admin', sysdate(), '', null, 'AI分析报告菜单');

-- 市场分析下将整车分析与车载分析分成两个独立目录
insert into sys_menu values('2203', '整车分析', '2200', '1', 'vehicle', null, '', '', 1, 0, 'M', '0', '0', '', 'dashboard', 'admin', sysdate(), '', null, '整车分析目录');
insert into sys_menu values('2201', '整车市场分析', '2203', '1', 'market', 'business/analysis/vehicle/index', '', 'VehicleMarket', 1, 0, 'C', '0', '0', 'business:analysis:vehicle:list', 'chart', 'admin', sysdate(), '', null, '整车市场分析菜单（支持页签缓存）');
insert into sys_menu values('2202', '车载分析', '2200', '2', 'display', null, '', '', 1, 0, 'M', '0', '0', '', 'monitor', 'admin', sysdate(), '', null, '车载分析目录');
insert into sys_menu values('2204', '车载市场分析', '2202', '1', 'onboard', 'business/analysis/display/index', '', 'OnboardMarket', 1, 0, 'C', '0', '0', 'business:data:excel:list', 'monitor', 'admin', sysdate(), '', null, '组长版完整车载市场解析与报告入口');

-- Excel、PDF和文本结构化保持在数据接入与解析目录
insert into sys_menu values('2101', 'Excel导入', '2100', '1', 'excel', 'business/data/excel/index', '', '', 1, 0, 'C', '0', '0', 'business:data:excel:list', 'excel', 'admin', sysdate(), '', null, '通用Excel与CSV解析、字段识别、预览和质量检查');
insert into sys_menu values('2102', 'PDF解析', '2100', '2', 'pdf', 'business/data/pdf/index', '', '', 1, 0, 'C', '0', '0', 'business:data:pdf:list', 'pdf', 'admin', sysdate(), '', null, 'PDF解析菜单');
insert into sys_menu values('2103', '文本结构化', '2100', '3', 'text', 'business/data/text/index', '', '', 1, 0, 'C', '0', '0', 'business:data:text:list', 'edit', 'admin', sysdate(), '', null, '文本结构化菜单');

-- 三级菜单：新闻中心
insert into sys_menu values('2301', '新闻采集', '2300', '1', 'collect', 'business/news/collect/index', '', '', 1, 0, 'C', '0', '0', 'business:news:collect:list', 'spider', 'admin', sysdate(), '', null, '新闻采集菜单');

-- 按钮权限：PDF解析
insert into sys_menu values('2120', 'PDF解析查询', '2102', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:pdf:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2121', 'PDF解析新增', '2102', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:pdf:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2122', 'PDF解析修改', '2102', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:pdf:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2123', 'PDF解析删除', '2102', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:pdf:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2124', 'PDF解析导出', '2102', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:pdf:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：文本结构化
insert into sys_menu values('2130', '文本结构化查询', '2103', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:text:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2131', '文本结构化新增', '2103', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:text:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2132', '文本结构化修改', '2103', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:text:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2133', '文本结构化删除', '2103', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:text:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2134', '文本结构化导出', '2103', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:text:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：整车市场分析
insert into sys_menu values('2210', '整车市场分析查询', '2201', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:analysis:vehicle:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2211', '整车市场分析新增', '2201', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:analysis:vehicle:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2212', '整车市场分析修改', '2201', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:analysis:vehicle:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2213', '整车市场分析删除', '2201', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:analysis:vehicle:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2214', '整车市场分析导出', '2201', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:analysis:vehicle:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：Excel导入
insert into sys_menu values('2110', 'Excel导入查询', '2101', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2111', 'Excel导入新增', '2101', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2112', 'Excel导入修改', '2101', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2113', 'Excel导入删除', '2101', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2114', 'Excel导入导出', '2101', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：车载市场分析（复用现有Excel解析与报告接口权限）
insert into sys_menu values('2230', '车载市场分析查询', '2204', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2231', '车载市场分析新增', '2204', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2232', '车载市场分析修改', '2204', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2233', '车载市场分析删除', '2204', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2234', '车载市场分析导出', '2204', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:data:excel:export', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2235', '车载报告查询', '2204', '6', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2236', '车载报告导出', '2204', '7', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：新闻采集
insert into sys_menu values('2310', '新闻采集查询', '2301', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:news:collect:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2311', '新闻采集新增', '2301', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:news:collect:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2312', '新闻采集修改', '2301', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:news:collect:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2313', '新闻采集删除', '2301', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:news:collect:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2314', '新闻采集导出', '2301', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:news:collect:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：固定知识库
insert into sys_menu values('2410', '固定知识库查询', '2400', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:knowledge:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2411', '固定知识库新增', '2400', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:knowledge:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2412', '固定知识库修改', '2400', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:knowledge:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2413', '固定知识库删除', '2400', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:knowledge:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2414', '固定知识库导出', '2400', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:knowledge:export', '#', 'admin', sysdate(), '', null, '');

-- 按钮权限：AI分析报告
insert into sys_menu values('2510', 'AI分析报告查询', '2500', '1', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:query', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2511', 'AI分析报告新增', '2500', '2', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:add', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2512', 'AI分析报告修改', '2500', '3', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:edit', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2513', 'AI分析报告删除', '2500', '4', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:remove', '#', 'admin', sysdate(), '', null, '');
insert into sys_menu values('2514', 'AI分析报告导出', '2500', '5', '', '', '', '', 1, 0, 'F', '0', '0', 'business:report:export', '#', 'admin', sysdate(), '', null, '');
