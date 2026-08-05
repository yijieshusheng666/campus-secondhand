# 校园二手交易平台

基于 Spring Boot 的校园二手商品交易平台，支持商品发布、浏览、搜索、购买及智能客服等功能。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5.13 |
| 开发语言 | Java 17 |
| 数据库 | MySQL 8.0 |
| ORM | Spring Data JPA (Hibernate) |
| JSON | Fastjson2 |
| 前端 | 原生 HTML + CSS + JavaScript |

## 功能模块

- **用户系统**：学号注册 / 登录
- **商品浏览**：按分类筛选、商品详情查看
- **商品发布**：支持多图上传
- **商品搜索**：关键词匹配推荐
- **购买下单**：地址管理、订单创建
- **智能客服**：关键词匹配 + 商品推荐
- **个人中心**：我的商品管理、订单管理

## 项目结构

```
├── demo1/                          # 后端 Spring Boot 项目
│   ├── src/main/java/com/example/demo/
│   │   ├── Demo1Application.java       # 启动类
│   │   ├── DataInitializer.java        # 数据初始化（测试数据）
│   │   ├── controller/
│   │   │   └── ApiController.java      # REST API 接口
│   │   ├── entity/
│   │   │   ├── Goods.java              # 商品实体
│   │   │   ├── GoodsImage.java         # 商品图片实体
│   │   │   └── User.java               # 用户实体
│   │   └── repository/
│   │       ├── GoodsRepository.java
│   │       ├── GoodsImageRepository.java
│   │       └── UserRepository.java
│   ├── src/main/resources/
│   │   └── application-template.properties  # 配置模板
│   └── pom.xml
├── school/                          # 前端页面
│   ├── index.html                   # 首页（商品浏览）
│   ├── login.html                   # 登录 / 注册
│   ├── publish.html                 # 发布商品
│   ├── user.html                    # 个人中心
│   ├── order.html                   # 订单管理
│   └── chat.html                    # 智能客服
└── README.md
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api` | 服务状态检查 |
| POST | `/api/register` | 用户注册 |
| POST | `/api/login` | 用户登录 |
| GET | `/api/goods` | 商品列表（支持 `?category=` 筛选） |
| GET | `/api/goods/{id}` | 商品详情 |
| POST | `/api/goods` | 发布商品（支持图片上传） |
| DELETE | `/api/goods/{id}` | 删除商品 |
| POST | `/api/recommend` | 关键词推荐商品 |

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Maven 3.6+

### 1. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS secondhand CHARACTER SET utf8mb4;
```

### 2. 修改配置

将 `demo1/src/main/resources/application-template.properties` 复制为 `application.properties`，修改数据库连接信息：

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/secondhand?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
spring.datasource.username=你的数据库用户名
spring.datasource.password=你的数据库密码
```

### 3. 启动后端

```bash
cd demo1
mvn spring-boot:run
```

或者用 IDEA 直接运行 `Demo1Application.java` 的 main 方法。

启动后访问：http://localhost:8080/api

### 4. 打开前端

用浏览器直接打开 `school/index.html`，或使用任意 HTTP 服务器：

```bash
cd school
npx serve .
```

### 5. 测试账号

| 账号 | 密码 |
|------|------|
| admin | admin123 |
| test | test123 |

## 注意事项

- 启动时 `DataInitializer` 会自动插入测试商品和用户数据
- 上传的图片存储在 `demo1/uploads/` 目录
- 数据库表结构由 JPA 自动创建（`ddl-auto=create-drop`），**每次重启会清空数据**
- 如需保留数据，请将 `application.properties` 中的 `ddl-auto` 改为 `update`
