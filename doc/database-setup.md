# 数据库初始化说明

## 1. 创建数据库

使用 MySQL 8.0 客户端执行：

```sql
CREATE DATABASE `ry-vue` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

## 2. 初始化表结构与数据

按顺序执行以下 SQL 脚本：

```bash
# 1. RuoYi-Vue 基础数据
mysql -u root -p ry-vue < sql/ry_20260417.sql

# 2. 业务模块表结构
mysql -u root -p ry-vue < sql/business_table.sql

# 3. 业务模块菜单权限
mysql -u root -p ry-vue < sql/business_menu.sql
```

如果是在已有数据库上升级知识问答功能（无需重建业务表），默认会在后端启动时幂等创建问答审计表。数据库账号没有 `CREATE TABLE` 权限时，请由数据库管理员执行一次增量脚本，并把 `business.knowledge.qa-schema-auto-init` 设为 `false`：

```bash
mysql -u root -p ry-vue < sql/business_knowledge_qa_audit.sql
```

该脚本创建问答任务、逐句结论和引用证据三张审计表，使任务在后端重启后仍可查询。

在已有数据库上合并新闻采集与 Python 文档解析能力时，后端默认通过原有 RuoYi
数据源在启动时幂等补齐表结构，无需再次填写数据库连接或手工导入。生产环境如果关闭
`business.news.schema-auto-init`，则执行一次：

```bash
mysql -u root -p ry-vue < sql/business_agent_integration_20260913.sql
```

该脚本只扩展新闻任务表并创建新闻文章/任务关系表。新闻正文随后由 Java 自动投影到现有统一知识库表，不创建第二套新闻知识库。

## 3. 配置后端数据库连接

数据库密码不要提交到 Git。启动前通过环境变量配置：

```powershell
$env:MYSQL_PASSWORD = "你的本机数据库密码"
```

`ruoyi-admin/src/main/resources/application-druid.yml` 使用以下占位配置：

```yaml
spring:
  datasource:
    druid:
      master:
        url: jdbc:mysql://localhost:3306/ry-vue?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=true&serverTimezone=GMT%2B8
        username: root
        password: ${MYSQL_PASSWORD:password}
```

## 4. 启动 Redis

RuoYi-Vue 依赖 Redis，请确保本地 Redis 已启动：

```bash
redis-server
```
