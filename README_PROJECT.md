# 京东业务分析平台 - RuoYi-Vue 基础框架

## 技术栈

- 后端：Spring Boot + MyBatis + Druid + MySQL 8.0 + Redis
- 前端：Vue 2 + Element UI + Axios
- 框架：RuoYi-Vue 3.9.2（前后端分离版）

## 目录结构

```
ruoyi-vue/
├── ruoyi-admin/          # Web 入口模块
├── ruoyi-business/       # 业务模块（新增）
│   └── src/main/java/com/ruoyi/business/
│       ├── data/         # 数据接入与解析
│       │   ├── excel/    # 原车载市场 Excel/CSV 解析与报告
│       │   ├── pdf/      # 文本型 PDF 解析
│       │   └── text/     # 自由文本结构化
│       ├── analysis/     # 市场分析
│       │   └── vehicle/  # 整车市场上传、解析、分析与 Java 安全网关
│       ├── news/         # 新闻中心
│       │   └── collect/  # 白名单新闻抓取、详情与知识库入库
│       ├── knowledge/    # 固定知识库
│       └── report/       # AI 分析与报告
│   ├── excel-agent/      # 原车载市场 Omdia 指标计算与竞争社报告
│   └── market-agent/     # 车载市场 Python 解析、分析及报告导出服务
├── ruoyi-common/         # 通用工具
├── ruoyi-framework/      # 框架配置
├── ruoyi-system/         # 系统模块
├── ruoyi-ui/             # 前端项目
│   └── src/
│       ├── api/business/ # 业务模块 API
│       └── views/business/ # 业务模块页面
├── sql/                  # 数据库脚本
│   ├── ry_20260417.sql   # RuoYi 基础数据
│   ├── business_table.sql # 业务表结构
│   └── business_menu.sql  # 业务菜单权限
└── doc/database-setup.md # 数据库初始化说明
```

## 已实现的 7 大业务模块

| 序号 | 菜单 | 模块 | 后端包 | 前端页面 |
| --- | --- | --- | --- | --- |
| 1 | PDF 解析 | business:data:pdf | com.ruoyi.business.data.pdf | views/business/data/pdf |
| 2 | 文本结构化 | business:data:text | com.ruoyi.business.data.text | views/business/data/text |
| 3 | 整车市场分析 | business:analysis:vehicle | com.ruoyi.business.analysis.vehicle | views/business/analysis/vehicle |
| 4 | 车载市场分析 | business:data:excel | com.ruoyi.business.data.excel | views/business/data/excel |
| 5 | 新闻采集 | business:news:collect | com.ruoyi.business.news.collect | views/business/news/collect |
| 6 | 固定知识库 | business:knowledge | com.ruoyi.business.knowledge | views/business/knowledge |
| 7 | AI 分析报告 | business:report | com.ruoyi.business.report | views/business/report |

整车市场分析使用 Market Agent 21.0：前端只访问受 Spring
Security 保护的 `/business/market/**`，Java 将上传任务转发到本机 8001
端口的 Python 服务。解析与分析均为异步任务，支持动态 Sheet、动态周期、图表组件、
Excel/Word/PPT 导出。分析任务及数据集索引保存到 MySQL，原始文件和导出物保存到
`MARKET_AGENT_STORAGE_DIR` 指定的持久目录。

原车载市场分析继续提供 Omdia Excel/CSV 解析、确定性指标计算和竞争社洞察报告生成，
入口与整车市场分析并列，两个模块不再互相替代。

## 快速启动

### 1. 数据库初始化

参考 `doc/database-setup.md`，按顺序执行 SQL：

```bash
mysql -u root -p ry-vue < sql/ry_20260417.sql
mysql -u root -p ry-vue < sql/business_table.sql
mysql -u root -p ry-vue < sql/business_menu.sql
```

### 2. 修改数据库配置

编辑 `ruoyi-admin/src/main/resources/application-druid.yml`，配置本机 MySQL 密码。

### 3. 启动 Redis

```bash
redis-server
```

### 4. 启动后端

LLM 配置在系统前端的统一配置页面维护并加密保存到 MySQL；车载市场分析、
知识问答共用该配置，不需要为 Market Agent 维护第二份密钥文件。首次启动前，已有
数据库请执行整车市场增量脚本：

```powershell
mysql -u root -p ry-vue < sql/business_vehicle_market_v21.sql
```

本项目默认也会在后端启动时幂等补齐整车市场表字段。LLM 未配置、请求超时或模型
输出校验失败时，解析任务会自动降级为确定性规则结果。

```bash
# 方式一：IDEA 中运行 RuoYiApplication
# 方式二：推荐的一键安全启动（自动检查 Java/Python/Redis 和两个 Python 服务）
powershell -ExecutionPolicy Bypass -File .\bin\run-safe.ps1
```

后端默认地址：http://localhost:8080

### 5. 启动前端

```bash
cd ruoyi-ui
npm install
npm run dev
```

前端默认地址：http://localhost:80

### 6. 登录

- 账号：admin
- 密码：admin123

登录后从「市场分析 → 车载分析」分别进入「整车市场分析」或「车载市场分析」。

## 验证结果

- 后端 `mvn -pl ruoyi-business -am test` 测试通过
- 前端 `npm run build:prod` 构建通过
- Market Agent `python -m pytest -q` 测试通过

## 后续开发建议

1. 车载市场文件解析及分析均通过受限后台任务执行，前端按任务状态轮询。
2. 根据实际业务扩展实体字段和数据库表结构。
3. 在 Controller 中增加文件上传、异步任务、回调等接口。
4. 前端页面替换为具体的业务交互（上传组件、解析结果展示、图表等）。
