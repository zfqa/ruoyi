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
