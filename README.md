# 校园二手交易平台

基于 Spring Boot + Vue 3 前后端分离的校园二手商品交易平台，支持商品发布、浏览、搜索、下单购买、订单管理及智能客服等功能。

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5.13 |
| 开发语言 | Java 17 |
| 数据库 | MySQL 8.0 |
| ORM | Spring Data JPA (Hibernate) |
| 前端框架 | Vue 3 + Vite |
| UI 组件库 | Element Plus |
| 前端路由/请求 | Vue Router + Axios |

## 功能模块

- **用户系统**：学号注册 / 登录，昵称修改（保存到数据库）
- **商品浏览**：按分类筛选、关键词搜索（服务端过滤）、商品详情查看
- **商品发布**：支持多图上传（最多 6 张）
- **商品状态**：下单自动标记"已售"，退款自动重新上架
- **购买下单**：收货地址选择、订单创建（订单持久化到数据库）
- **订单管理**：买家订单 / 卖家售出记录、标记发货、申请退款
- **智能客服**：全局悬浮按钮 + 抽屉面板，关键词匹配 + 商品推荐卡片，任意页面随时提问

## 项目结构

```
├── demo1/                              # 后端 Spring Boot 项目
│   ├── src/main/java/com/example/demo/
│   │   ├── Demo1Application.java       # 启动类（CORS、uploads 静态资源映射）
│   │   ├── DataInitializer.java        # 数据初始化（测试数据）
│   │   ├── controller/
│   │   │   ├── ApiController.java      # 商品 / 用户 / 推荐接口
│   │   │   └── OrderController.java    # 订单接口（创建 / 查询 / 状态流转）
│   │   ├── entity/
│   │   │   ├── Goods.java              # 商品实体（含 status 在售/已售）
│   │   │   ├── GoodsImage.java         # 商品图片实体
│   │   │   ├── Order.java              # 订单实体
│   │   │   └── User.java               # 用户实体
│   │   └── repository/                 # Spring Data JPA 仓库
│   ├── src/main/resources/
│   │   ├── application.properties      # 数据库等配置
│   │   └── application-template.properties
│   └── pom.xml
├── frontend/                           # 前端 Vue 3 项目
│   ├── src/
│   │   ├── App.vue                     # 整体布局（导航栏、悬浮客服按钮 + 抽屉）
│   │   ├── api.js                      # Axios 封装（/api 代理到 8080）
│   │   ├── router.js                   # 路由配置
│   │   ├── components/
│   │   │   └── ChatPanel.vue           # 智能客服面板（悬浮窗内使用）
│   │   └── views/
│   │       ├── Home.vue                # 首页（商品浏览、搜索、下单）
│   │       ├── Login.vue               # 登录 / 注册
│   │       ├── Publish.vue             # 发布商品
│   │       ├── Orders.vue              # 我的订单
│   │       └── User.vue                # 个人中心（我的商品、售出记录、地址）
│   ├── vite.config.js                  # Vite 配置（/api、/uploads 代理）
│   └── package.json
├── school/                             # 旧版静态页面（已被 Vue 前端替代，保留备份）
└── README.md
```

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api` | 服务状态检查 |
| POST | `/api/register` | 用户注册 |
| POST | `/api/login` | 用户登录 |
| POST | `/api/user/update` | 修改昵称 |
| GET | `/api/goods` | 商品列表（`?category=`、`?keyword=`，已售自动过滤） |
| GET | `/api/goods/{id}` | 商品详情 |
| POST | `/api/goods` | 发布商品（支持图片上传） |
| DELETE | `/api/goods/{id}` | 删除商品 |
| POST | `/api/recommend` | 关键词推荐商品 |
| POST | `/api/orders` | 创建订单（商品自动标记已售） |
| GET | `/api/orders` | 查询订单（`?userId=&role=buyer/seller`） |
| PUT | `/api/orders/{id}/status` | 更新订单状态（退款时商品重新上架） |

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Maven 3.6+
- Node.js 18+

### 1. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS secondhand CHARACTER SET utf8mb4;
```

### 2. 修改后端配置

编辑 `demo1/src/main/resources/application.properties`，修改数据库连接信息：

```properties
spring.datasource.username=你的数据库用户名
spring.datasource.password=你的数据库密码
```

### 3. 启动后端

```bash
cd demo1
mvn spring-boot:run
```

启动后接口地址：http://localhost:8080/api

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

启动后访问：http://localhost:5173 （已配置代理，无需处理跨域）

### 5. 测试账号

| 账号 | 密码 |
|------|------|
| admin | admin123 |
| test | test123 |

## 注意事项

- 启动时 `DataInitializer` 会自动插入测试商品和用户数据（仅当表为空时）
- 上传的图片存储在 `demo1/uploads/` 目录，通过 `/uploads/**` 访问
- `ddl-auto=update` 模式下重启不会清空数据
- 收货地址保存在浏览器 localStorage 中，订单保存在数据库
