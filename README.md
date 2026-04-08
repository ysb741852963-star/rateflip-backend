# RateFlip Backend

Exchange Rate API Service with Redis Cache

## 技术栈

- Java 17
- Spring Boot 3.2
- Redis (缓存)
- Maven

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/rates` | GET | 获取汇率（自动缓存） |
| `/api/v1/rates/refresh` | GET | 强制刷新汇率 |
| `/api/v1/rates/status` | GET | 检查缓存状态 |
| `/api/v1/health` | GET | 健康检查 |

### 示例

```bash
# 获取 USD 汇率
curl http://localhost:8080/api/v1/rates?base=USD

# 获取 EUR 汇率
curl http://localhost:8080/api/v1/rates?base=EUR

# 强制刷新
curl http://localhost:8080/api/v1/rates/refresh?base=USD

# 检查缓存状态
curl http://localhost:8080/api/v1/rates/status?base=USD
```

### 响应示例

```json
{
  "success": true,
  "data": {
    "base": "USD",
    "timestamp": 1707123456,
    "rates": {
      "CNY": 7.23,
      "EUR": 0.92,
      "GBP": 0.79,
      "JPY": 148.50,
      "CAD": 1.35
    },
    "cached": true,
    "cacheAgeSeconds": 1800
  }
}
```

## 配置

配置文件：`src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  data:
    redis:
      host: localhost
      port: 6379

cache:
  ttl:
    seconds: 3600  # 1小时缓存
```

## 运行

### 1. 确保 Redis 运行

```bash
# macOS
brew services start redis

# Linux
sudo systemctl start redis

# Windows
redis-server
```

### 2. 打包运行

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/rateflip-backend-1.0.0.jar
```

### 3. 开发模式

```bash
mvn spring-boot:run
```

## 项目结构

```
src/main/java/com/rateflip/backend/
├── RateFlipApplication.java    # 启动类
├── config/
│   ├── RedisConfig.java       # Redis 配置
│   └── WebConfig.java         # Web 配置 (CORS)
├── controller/
│   └── ExchangeRateController.java  # REST 控制器
├── service/
│   ├── ExchangeRateService.java     # 业务逻辑 + 缓存
│   └── ExternalApiClient.java       # 外部 API 调用
└── model/
    ├── ExchangeRate.java       # 汇率模型
    └── ApiResponse.java        # 响应封装
```

## 缓存策略

- TTL: 1 小时
- 缓存键: `rateflip:rates:{BASE_CURRENCY}`
- 当缓存存在且未过期时直接返回
- 缓存过期时自动从 open.er-api.com 获取新数据
- 外部 API 失败时返回过期缓存（stale flag）

## 外部 API

使用 [open.er-api.com](https://open.er-api.com/) 免费 API

- 免费额度: 1000 次/月
- 无需 API Key
- 支持所有主流货币
