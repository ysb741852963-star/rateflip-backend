# 汇率API备用源与熔断器实现方案

## 📋 概述

本方案为主API（open.er-api.com）实现了完整的备用API切换机制，采用熔断器模式，支持自动切换和恢复。

---

## 🏗️ 架构设计

### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                    ExchangeRateService                       │
│                  (统一入口，带缓存和容错)                       │
└──────────────────────────┬──────────────────────────────────┘
                           │
           ┌───────────────┼───────────────┐
           │               │               │
           ▼               ▼               ▼
    ┌────────────┐  ┌────────────┐  ┌────────────┐
    │   Redis    │  │  Primary   │  │  Fallback  │
    │   Cache    │  │  API Client│  │  API Client│
    │            │  │ (er-api.com)│ │(frankfurter)│
    └────────────┘  └─────┬──────┘  └────────────┘
                          │
                          ▼
                   ┌────────────┐
                   │CircuitBreaker│
                   │  (熔断器)    │
                   └────────────┘
```

### 熔断器状态机

```
                    ┌─────────────┐
                    │   CLOSED    │ ◄────────────────┐
                    │  (正常)      │                  │
                    └──────┬──────┘                  │
                           │                         │
              连续失败≥阈值│                         │连续成功≥阈值
                           ▼                         │
                    ┌─────────────┐                  │
                    │    OPEN     │                  │
                    │  (熔断)      │──────────────────┘
                    └──────┬──────┘
                           │
              超时后自动   │    探测请求成功
              进入半开状态  ▼
                    ┌─────────────┐
                    │  HALF_OPEN  │
                    │  (半开)      │
                    └─────────────┘
```

---

## 📁 文件清单

| 文件路径 | 说明 |
|---------|------|
| `service/CircuitBreaker.java` | 熔断器核心实现 |
| `service/CircuitState.java` | 熔断器状态枚举 |
| `service/FallbackExchangeRateClient.java` | 备用API客户端 |
| `service/ExternalApiClient.java` | 主API客户端（已重构）|
| `service/ExchangeRateService.java` | 业务服务层（已重构）|
| `model/ExchangeRate.java` | 数据模型（已添加fallbackSource字段）|
| `controller/ExchangeRateController.java` | API控制器（已添加新端点）|
| `resources/application.yml` | 配置文件（已添加熔断器和备用API配置）|

---

## ⚙️ 配置说明

### application.yml 配置项

```yaml
# 主API配置
exchange:
  api:
    base-url: https://open.er-api.com
    endpoint: /v6/latest
    timeout: 10000

    # 备用API配置
    fallback:
      base-url: https://api.frankfurter.app
      endpoint: /v1/latest

# 熔断器配置
circuitbreaker:
  failure-threshold: 3        # 连续失败次数阈值（默认3次）
  open-timeout-millis: 30000   # 熔断持续时间30秒
  success-threshold: 2         # 半开状态恢复阈值（默认2次）
```

### 配置参数说明

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `failure-threshold` | 3 | 连续失败N次后触发熔断 |
| `open-timeout-millis` | 30000 | 熔断持续时间（毫秒）|
| `success-threshold` | 2 | 半开状态下连续成功N次后关闭熔断 |

---

## 🔄 切换策略

### 触发条件（自动切换到备用API）

1. **主API返回5xx错误** - 服务器内部错误
2. **主API返回429错误** - 请求频率超限
3. **主API请求超时** - 连接超时或读取超时
4. **主API解析失败** - 响应格式异常
5. **熔断器打开** - 连续失败达到阈值

### 恢复策略（自动切回主API）

1. 熔断器打开持续 `open-timeout-millis` 后自动进入**半开状态**
2. 半开状态下允许探测性请求通过
3. 连续成功 `success-threshold` 次后关闭熔断，恢复正常

---

## 📡 新增API端点

### 1. 获取API源状态
```
GET /api/v1/rates/source-status

响应示例:
{
  "code": 0,
  "data": {
    "currentSource": "primary",    // primary | fallback | none
    "primaryAvailable": true,
    "fallbackAvailable": true,
    "circuitStats": {
      "state": "CLOSED",           // CLOSED | OPEN | HALF_OPEN
      "consecutiveFailures": 0,
      "consecutiveSuccesses": 0,
      "totalRequests": 100,
      "totalSuccesses": 98,
      "totalFailures": 2,
      "circuitOpenedCount": 0
    }
  }
}
```

### 2. 重置熔断器（紧急恢复）
```
POST /api/v1/rates/circuit-breaker/reset

响应:
{
  "code": 0,
  "data": "熔断器已重置"
}
```

---

## 🧪 测试验证建议

### 1. 正常流程测试
```bash
# 测试正常获取汇率
curl "http://localhost:8080/api/v1/rates?base=USD"

# 验证返回数据包含 fallbackSource 字段
```

### 2. 熔断器触发测试
```bash
# 模拟主API故障（通过配置错误的base-url）
# 连续请求3次，观察熔断器状态变化

# 检查熔断器状态
curl "http://localhost:8080/api/v1/rates/source-status"

# 验证已切换到备用源
curl "http://localhost:8080/api/v1/rates?base=USD"
```

### 3. 熔断器恢复测试
```bash
# 等待30秒（熔断超时时间）
# 或者手动重置熔断器
curl -X POST "http://localhost:8080/api/v1/rates/circuit-breaker/reset"

# 验证恢复使用主API
curl "http://localhost:8080/api/v1/rates/source-status"
```

### 4. 备用API直接测试
```bash
# 验证备用API可用性
curl "https://api.frankfurter.app/v1/latest?from=USD"

# 预期响应格式:
# {"base":"USD","date":"2026-04-08","rates":{"EUR":0.92,...}}
```

### 5. 单元测试示例
```java
@Test
void testCircuitBreakerOpensAfterThreshold() {
    CircuitBreaker breaker = new CircuitBreaker(3, 30000, 2);
    
    // 前3次失败不应打开熔断
    breaker.recordFailure();
    breaker.recordFailure();
    assertTrue(breaker.allowRequest());
    
    // 第3次失败应打开熔断
    breaker.recordFailure();
    assertFalse(breaker.allowRequest());
    assertEquals(CircuitState.OPEN, breaker.getState());
}

@Test
void testCircuitBreakerRecoversAfterSuccess() {
    CircuitBreaker breaker = new CircuitBreaker(3, 100, 2);
    
    // 打开熔断器
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    assertFalse(breaker.allowRequest());
    
    // 模拟超时（实际上测试中可以直接重置状态）
    // 等待超时后进入半开状态
    // 连续2次成功后关闭
    breaker.recordSuccess();
    breaker.recordSuccess();
    assertEquals(CircuitState.CLOSED, breaker.getState());
}
```

---

## 🔧 注意事项

### 1. 备用API限制
- frankfurter.app 是免费开源API，有请求频率限制
- 仅支持部分主要货币（EUR, GBP, JPY, CHF等约30种）
- 不支持加密货币和小众货币

### 2. 数据一致性
- 两个API的数据可能存在细微差异（汇率实时波动）
- `fallbackSource=true` 标记表示数据来自备用API

### 3. 生产环境建议
- 考虑接入Redis Sentinel提高缓存可用性
- 备用API可以配置多个形成链式 fallback
- 添加监控告警（熔断器打开次数统计）
- 日志中标记数据来源便于排查问题

---

## 📊 监控指标

建议添加以下监控指标：

| 指标名 | 说明 | 告警阈值 |
|--------|------|----------|
| `circuit_breaker_open_count` | 熔断器打开次数 | > 10次/小时 |
| `api_primary_failure_rate` | 主API失败率 | > 5% |
| `api_fallback_usage_rate` | 备用API使用率 | > 30% |
| `api_latency_p95` | API延迟P95 | > 5s |

---

## 🚀 快速验证清单

- [ ] 启动应用，验证主API正常工作
- [ ] 配置错误的base-url，触发主API失败
- [ ] 连续请求3次，观察熔断器状态变为 OPEN
- [ ] 第4次请求应自动切换到备用API
- [ ] 等待30秒或手动重置熔断器
- [ ] 验证恢复使用主API
- [ ] 检查日志中的熔断器和数据源切换记录
