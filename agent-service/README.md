# 新闻采集与文档解析侧车（POC）

本服务由同事的新闻采集、PDF/PPTX/TXT 解析代码整理而来，只承担采集与解析。RuoYi Java 服务是控制面，现有 MySQL `business_knowledge`、`business_kb_version`、`business_kb_chunk` 是唯一正式知识库。

## 约束

- `/data/upload` 默认且在 Java 调用中强制 `index_to_kb=false`、`persist_review=false`。
- Python 不提供 Chroma/本地审核库接口；页码、表格、图表、实体和语义切片以 JSON 返回 Java。
- 文档解析所需 LLM 地址、模型和 API Key 由 Java 的统一 LLM 配置按内部请求下发，不读取同事机器的密钥。
- 新闻抓取允许使用 SQLite 保存采集运行缓存与去重状态；正式问答、引用和图谱数据需在 RuoYi 前端手动「入库」后，由 Java 发布到现有 MySQL 知识库。
- 所有 Java→Python 接口使用 `AGENT_INTERNAL_TOKEN`，浏览器不直连本服务。

## 安装与启动

```powershell
python -m pip install -r .\agent-service\requirements.txt
python -m playwright install chromium
powershell -ExecutionPolicy Bypass -File .\bin\run-safe.ps1
```

`run-safe.ps1` 会检查并启动本侧车，再启动 Java 后端。前端仍按项目原方式启动。

## 主要接口

- `GET /health`：健康检查。
- `GET /news/sources`：返回启用的白名单新闻源（需内部令牌）。
- `POST /news/crawl`：采集新闻并把本次文章返回 Java（需内部令牌）。
- `POST /data/upload`：解析 PDF/PPTX/TXT 并返回可溯源结构（需内部令牌）。

运行时目录、`.env`、SQLite 文件、日志、浏览器组件和虚拟环境均不得提交 Git。
