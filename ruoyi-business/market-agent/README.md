# 整车市场分析Python服务

该服务为“车载市场分析”页面提供文件上传、异步解析、数据集分析、报告编排及导出能力。
独立 Excel 解析页面和 `parse/jobs` 网关已经移除；车载市场文件统一调用
`upload/jobs`，解析成功后创建可持续使用的数据集。

浏览器不得直接访问本服务，统一经过Spring Boot的/business/market网关。

本机启动：

    python -m pip install -r requirements.txt
    python run_backend.py

默认健康检查：

    http://127.0.0.1:8001/api/health

生产环境请复制.env.example并填写独立STORAGE_DIR。不要提交真实.env、密钥、上传数据、报告、缓存或虚拟环境。
