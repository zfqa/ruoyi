# 整车市场分析 Market Agent 21.0 集成说明

## 边界与调用链

浏览器只调用若依后端 `/business/market/**`。Java 网关负责登录鉴权、权限校验、
数据集登记、文件名处理以及统一 LLM 配置下发，再访问仅监听 `127.0.0.1:8001`
的 Python Market Agent。浏览器不直接连接 Python 服务。

整车市场分析页面调用 `upload/jobs` 完成文件上传与解析，
成功后在 MySQL 登记可复用的数据集。分析、对话、报告规划和
Excel/Word/PPT 导出均以数据集 ID 为入口。

通用 Excel 导入保留在“数据接入与解析”目录。车载市场分析作为独立双页签页面：
“整车市场数据”复用增强导入组件，“车载显示数据（原功能）”用于 Omdia 三文件上传、
确定性指标计算、历史任务、首份报告查看和 Word/PPT 下载；原功能使用
`/business/data/excel/**`，不经过 Market Agent 21.0。

## 配置

- Java 网关：`business.market-agent.*`，位于 `application.yml`。
- Python 存储：环境变量 `MARKET_AGENT_STORAGE_DIR`；默认一键启动使用
  `D:\ruoyi\market-agent-storage`，不要放在源码目录或提交到 Git。
- LLM：使用系统前端统一 LLM 配置。密钥由 Java 在内部请求头中传递，只驻留 Python
  进程内存，不写入 Market Agent 的 `.env` 或文件系统。
- 上传总量：Spring multipart 和 Python 服务均允许最多 200 MB；文件类型仍由接口按
  场景校验。

## 启动

先完成 Maven 打包，然后在项目根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\bin\run-safe.ps1
```

脚本会检查 Java 17、可用 Python、Redis、新闻/PDF 服务 8000、Market Agent 8001、
上传目录写权限，然后启动若依后端。Market Agent 健康检查要求版本严格为 21.0，避免
端口被其他程序占用时误判为启动成功。

## 数据库升级与回滚

已有数据库先执行 `sql/business_vehicle_market_v21.sql` 补齐整车市场字段，再执行
`sql/patch_vehicle_analysis_menu.sql` 更新双入口菜单。两个迁移均不删除历史业务记录。
回滚应用时可恢复旧前后端代码并停止 8001 服务；新增列可保留，不影响旧版。
除非已有完整备份且确认不再需要新任务数据，不应删除新增列、数据集目录或导出文件。

## 验证

```powershell
mvn -pl ruoyi-business -am test
cd ruoyi-ui
npm run build:prod
cd ..\ruoyi-business\market-agent
python -m pytest -q
cd ..\excel-agent
python -m pytest -q
```

完整 Java→Python 回归需先启动 8001 服务，并设置 `RUN_MARKET_AGENT_IT=true` 与
`MARKET_AGENT_IT_FILE` 后执行 `MarketAgentControllerIntegrationTest`。
