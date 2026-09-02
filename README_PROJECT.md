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
│       │   ├── excel/    # Excel/CSV 导入与字段映射
│       │   ├── pdf/      # 文本型 PDF 解析
│       │   └── text/     # 自由文本结构化
│       ├── analysis/     # 市场分析
│       │   ├── vehicle/  # 整车市场分析
│       │   └── display/  # 车载显示分析
│       ├── news/         # 新闻中心
│       │   ├── collect/  # 白名单新闻抓取
│       │   └── process/  # 新闻清洗、分类与事件提取
│       ├── knowledge/    # 固定知识库
│       └── report/       # AI 分析与报告
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

## 已预留的 9 大业务模块

| 序号 | 菜单 | 模块 | 后端包 | 前端页面 |
| --- | --- | --- | --- | --- |
| 1 | Excel/CSV 导入 | business:data:excel | com.ruoyi.business.data.excel | views/business/data/excel |
| 2 | PDF 解析 | business:data:pdf | com.ruoyi.business.data.pdf | views/business/data/pdf |
| 3 | 文本结构化 | business:data:text | com.ruoyi.business.data.text | views/business/data/text |
| 4 | 整车市场分析 | business:analysis:vehicle | com.ruoyi.business.analysis.vehicle | views/business/analysis/vehicle |
| 5 | 车载显示分析 | business:analysis:display | com.ruoyi.business.analysis.display | views/business/analysis/display |
| 6 | 新闻采集 | business:news:collect | com.ruoyi.business.news.collect | views/business/news/collect |
| 7 | 新闻处理 | business:news:process | com.ruoyi.business.news.process | views/business/news/process |
| 8 | 固定知识库 | business:knowledge | com.ruoyi.business.knowledge | views/business/knowledge |
| 9 | AI 分析报告 | business:report | com.ruoyi.business.report | views/business/report |

每个模块已包含：
- 实体类（Domain）
- Mapper 接口 + XML
- Service 接口 + 实现
- Controller（预留 list/export/get/add/edit/remove 接口）
- 前端 API 文件
- 前端 index.vue 页面（列表 + 增删改查弹窗）
- 数据库表
- 菜单权限数据

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

如需启用 Excel 表格的 LLM 结构增强，请先在启动后端的同一终端设置火山方舟 API Key：

```powershell
$env:ARK_API_KEY = "你的API Key"
$env:ARK_API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
$env:ARK_MODEL = "deepseek-v4-flash-ga-260731"
```

API Key 只从环境变量读取；未配置、请求超时或模型输出校验失败时，解析任务会自动降级为规则解析。

```bash
# 方式一：IDEA 中运行 RuoYiApplication
# 方式二：命令行
mvn -pl ruoyi-admin -am spring-boot:run
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

登录后左侧菜单会出现「业务模块」目录，包含 9 个业务功能页面。

## 验证结果

- 后端 `mvn clean compile -DskipTests` 编译通过
- 前端 `npm run build:prod` 构建通过

## 后续开发建议

1. Excel/CSV 解析已通过受限后台任务执行，前端按任务状态轮询；其他模块继续在 ServiceImpl 中接入实际算法（PDF 解析、文本结构化、爬虫、AI 报告生成等）。
2. 根据实际业务扩展实体字段和数据库表结构。
3. 在 Controller 中增加文件上传、异步任务、回调等接口。
4. 前端页面替换为具体的业务交互（上传组件、解析结果展示、图表等）。
