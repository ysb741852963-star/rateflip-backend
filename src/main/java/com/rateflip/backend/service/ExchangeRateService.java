package com.rateflip.backend.service;

import com.rateflip.backend.model.ExchangeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 汇率服务类（统一USD缓存 + 交叉汇率计算）
 * 缓存键统一为 rateflip:rates:USD，所有货币请求都只调用一次API(USD)
 * 非USD货币通过 1.0/rates[货币] 交叉计算得出
 */
@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);
    
    // 缓存键前缀
    private static final String CACHE_KEY_PREFIX = "rateflip:rates:";
    // 备用源缓存键后缀
    private static final String FALLBACK_CACHE_SUFFIX = ":fallback";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ExternalApiClient externalApiClient;
    private final FallbackExchangeRateClient fallbackClient;
    private final CircuitBreaker circuitBreaker;

    // 缓存有效期（秒），从配置文件读取
    @Value("${cache.ttl.seconds}")
    private long cacheTtlSeconds;

    @Autowired
    public ExchangeRateService(
            RedisTemplate<String, Object> redisTemplate,
            ExternalApiClient externalApiClient,
            FallbackExchangeRateClient fallbackClient,
            @Autowired(required = false) CircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.externalApiClient = externalApiClient;
        this.fallbackClient = fallbackClient;
        // 如果没有配置熔断器，创建一个默认的
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreaker(3, 30000, 2);
    }

    /**
     * 获取汇率（统一使用USD作为数据源，单一缓存键）
     * 缓存键统一为 rateflip:rates:USD
     * 非USD货币通过 1.0/rates[货币] 交叉计算得出
     * @param baseCurrency 基准货币代码（任意货币，实际数据源始终为USD）
     * @return ExchangeRate 汇率对象（base始终为USD，但rates中的货币由请求决定）
     */
    public ExchangeRate getRates(String baseCurrency) {
        String usdCacheKey = CACHE_KEY_PREFIX + "USD";
        String requestedCurrency = baseCurrency.toUpperCase();
        // 1. 尝试从 Redis 获取 USD 基准缓存
        try {
            ExchangeRate usdCached = (ExchangeRate) redisTemplate.opsForValue().get(usdCacheKey);
            
            if (usdCached != null) {
                long now = System.currentTimeMillis() / 1000;
                int cacheAge = (int) (now - usdCached.getTimestamp());
                // 如果请求的就是USD，直接返回
                if ("USD".equals(requestedCurrency)) {
                    usdCached.setCacheAgeSeconds(cacheAge);
                    usdCached.setCached(true);
                    usdCached.setStale(false);
                    return usdCached;
                }
                
                // 非USD货币：从USD缓存计算交叉汇率
                // API返回的usdCached.rates[currency]表示 1 USD = ? currency
                // 所以 requestedCurrency 的汇率 = 1.0 / usdCached.rates[requestedCurrency]
                return computeCrossRate(usdCached, requestedCurrency, cacheAge, false);
            }
        } catch (Exception e) {
            logger.warn("Redis 获取失败: {}", e.getMessage());
        }

        // 2. USD 缓存未命中，调用 API 获取 USD 汇率并缓存
        logger.info("USD 缓存未命中，调用 API 获取 USD 汇率");
        return fetchAndCacheUsdRates(usdCacheKey, requestedCurrency);
    }

    /**
     * 从 USD 缓存计算指定货币的交叉汇率
     * @param usdCached    USD 汇率缓存数据
     * @param targetCurrency 目标货币代码
     * @param cacheAge     缓存年龄（秒）
     * @param isFallback   是否来自备用源
     * @return ExchangeRate 汇率对象，base 为 targetCurrency，rate 为 1 USD = ? targetCurrency
     */
    private ExchangeRate computeCrossRate(ExchangeRate usdCached, String targetCurrency, int cacheAge, boolean isFallback) {
        Double usdToTarget = usdCached.getRates().get(targetCurrency);
        if (usdToTarget == null) {
            logger.warn("USD 缓存中不包含货币: {}，无法计算交叉汇率", targetCurrency);
            throw new RuntimeException("USD 缓存中不包含货币: " + targetCurrency);
        }
        
        // usdCached.rates[currency] = N 表示 "1 USD = N currency"
        // 例如: usdRates["EUR"] = 0.92 表示 "1 USD = 0.92 EUR"
        //
        // 要计算 "以 targetCurrency 为 base 的汇率":
        // rates[currency] = usdRates[currency] / usdRates[targetCurrency]
        // 
        // 例如 targetCurrency=EUR, currency=USD:
        //   = usdRates["USD"] / usdRates["EUR"] = 1.0 / 0.92 = 1.087
        //   即: 1 EUR = 1.087 USD ✓
        //
        // 例如 targetCurrency=EUR, currency=GBP:
        //   = usdRates["GBP"] / usdRates["EUR"] = 0.79 / 0.92 = 0.859
        //   即: 1 EUR = 0.859 GBP ✓
        
        Map<String, Double> computedRates = new HashMap<>();
        for (Map.Entry<String, Double> entry : usdCached.getRates().entrySet()) {
            String currency = entry.getKey();
            double usdRate = entry.getValue();
            computedRates.put(currency, usdRate / usdToTarget);
        }
        // targetCurrency 自身的汇率为 1.0
        computedRates.put(targetCurrency, 1.0);
        
        ExchangeRate result = new ExchangeRate();
        result.setBase(targetCurrency);
        result.setTimestamp(usdCached.getTimestamp());
        result.setRates(computedRates);
        result.setCacheAgeSeconds(cacheAge);
        result.setCached(true);
        result.setStale(false);
        result.setFallbackSource(isFallback);
        
        logger.info("交叉汇率计算完成，基准货币: {}, USD 缓存年龄: {} 秒", targetCurrency, cacheAge);
        return result;
    }

    /**
     * 强制刷新汇率（强制刷新 USD 缓存，然后计算目标货币）
     * @param baseCurrency 基准货币代码
     * @return ExchangeRate 汇率对象
     */
    public ExchangeRate refreshRates(String baseCurrency) {
        String usdCacheKey = CACHE_KEY_PREFIX + "USD";
        String requestedCurrency = baseCurrency.toUpperCase();
        
        logger.info("强制刷新汇率，请求货币: {}", requestedCurrency);
        return fetchAndCacheUsdRates(usdCacheKey, requestedCurrency);
    }

    /**
     * 获取 USD 汇率数据并缓存（带熔断和备用API支持）
     * 优先级：主API -> 备用API -> 过期缓存 -> 抛出异常
     * 缓存键始终为 rateflip:rates:USD
     */
    private ExchangeRate fetchAndCacheUsdRates(String usdCacheKey, String requestedCurrency) {
        ExchangeRate usdResult = null;
        String source = "unknown";

        // 策略：优先尝试主API，如果熔断器打开或失败，则使用备用API
        try {
            // 检查是否应该使用备用源（基于熔断器状态）
            if (!circuitBreaker.allowRequest()) {
                logger.warn("主API熔断器已打开，尝试使用备用API");
                throw new ExternalApiClient.ApiException("主API熔断器已打开", 
                        ExternalApiClient.ApiException.Type.CIRCUIT_OPEN);
            }

            // 始终调用 api.getRates("USD")，因为统一使用USD作为数据源
            usdResult = externalApiClient.fetchRates("USD");
            source = "primary";
            
        } catch (ExternalApiClient.ApiException primaryError) {
            logger.warn("主API调用失败: {}, type={}", primaryError.getMessage(), primaryError.getType());

            // 根据错误类型决定是否尝试备用API
            if (shouldTryFallback(primaryError)) {
                try {
                    logger.info("尝试使用备用API获取汇率");
                    usdResult = fallbackClient.fetchRates("USD");
                    source = "fallback";
                    
                    // 备用API成功，记录主API失败（用于熔断器）
                    circuitBreaker.recordFailure();
                    
                } catch (Exception fallbackError) {
                    logger.error("备用API也失败了: {}", fallbackError.getMessage());
                    // 两个API都失败了
                }
            } else {
                // 非可重试错误（如熔断器打开），直接记录失败
                circuitBreaker.recordFailure();
            }
        }

        // 如果获取到了 USD 数据，缓存并返回
        if (usdResult != null) {
            try {
                // 始终缓存到 USD 键
                redisTemplate.opsForValue().set(usdCacheKey, usdResult, cacheTtlSeconds, TimeUnit.SECONDS);
                logger.info("已缓存 USD 汇率，TTL: {} 秒, 来源: {}", cacheTtlSeconds, source);
            } catch (Exception e) {
                logger.warn("Redis 写入失败: {}", e.getMessage());
            }
            
            usdResult.setCached(false);
            usdResult.setFallbackSource("fallback".equals(source));
            
            // 如果请求的就是USD，直接返回
            if ("USD".equals(requestedCurrency)) {
                return usdResult;
            }
            
            // 非USD货币：计算交叉汇率
            return computeCrossRate(usdResult, requestedCurrency, 0, "fallback".equals(source));
        }

        // 两个API都失败了，尝试返回过期USD缓存
        logger.warn("两个API都失败了，尝试返回过期USD缓存");
        return tryGetStaleUsdCache(usdCacheKey, requestedCurrency);
    }

    /**
     * 判断是否应该尝试备用API
     */
    private boolean shouldTryFallback(ExternalApiClient.ApiException error) {
        // 以下错误类型值得尝试备用API
        return error.isRetryable() || 
               error.getType() == ExternalApiClient.ApiException.Type.CIRCUIT_OPEN ||
               error.getType() == ExternalApiClient.ApiException.Type.UNKNOWN;
    }

    /**
     * 尝试获取过期USD缓存作为最后保底，然后计算目标货币交叉汇率
     */
    private ExchangeRate tryGetStaleUsdCache(String usdCacheKey, String requestedCurrency) {
        try {
            ExchangeRate stale = (ExchangeRate) redisTemplate.opsForValue().get(usdCacheKey);
            if (stale != null) {
                long now = System.currentTimeMillis() / 1000;
                int cacheAge = (int) (now - stale.getTimestamp());
                stale.setCached(true);
                stale.setStale(true);
                
                logger.info("返回过期USD缓存作为保底，缓存年龄: {} 秒，请求货币: {}", cacheAge, requestedCurrency);
                
                if ("USD".equals(requestedCurrency)) {
                    stale.setCacheAgeSeconds(cacheAge);
                    return stale;
                }
                
                // 计算目标货币的交叉汇率
                return computeCrossRate(stale, requestedCurrency, cacheAge, true);
            }
        } catch (Exception ex) {
            logger.warn("获取过期缓存失败: {}", ex.getMessage());
        }
        
        throw new RuntimeException("获取汇率失败: 主API和备用API都不可用，且无缓存数据");
    }

    /**
     * 检查 USD 缓存是否新鲜（用于"已是最新"功能）
     * 注意：现在统一使用 USD 缓存，所以检查的是 USD 缓存新鲜度
     * @param baseCurrency 基准货币代码（任意货币，都检查 USD 缓存）
     * @return true 如果 USD 缓存新鲜（在 TTL 内）
     */
    public boolean isCacheFresh(String baseCurrency) {
        String usdCacheKey = CACHE_KEY_PREFIX + "USD";
        
        try {
            ExchangeRate cached = (ExchangeRate) redisTemplate.opsForValue().get(usdCacheKey);
            if (cached == null) {
                return false;
            }
            
            long now = System.currentTimeMillis() / 1000;
            long cacheAge = now - cached.getTimestamp();
            
            return cacheAge < cacheTtlSeconds;
        } catch (Exception e) {
            logger.warn("检查缓存新鲜度出错: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取熔断器状态信息
     */
    public CircuitBreaker.CircuitStats getCircuitBreakerStats() {
        return circuitBreaker.getStats();
    }

    /**
     * 手动重置熔断器
     */
    public void resetCircuitBreaker() {
        circuitBreaker.reset();
        logger.info("熔断器已手动重置");
    }

    /**
     * 获取当前数据源状态
     */
    public ApiSourceStatus getApiSourceStatus() {
        CircuitBreaker.CircuitStats stats = circuitBreaker.getStats();
        boolean primaryAvailable = externalApiClient.isAvailable();
        boolean fallbackAvailable = fallbackClient.isAvailable();

        String currentSource;
        if (stats.state() == CircuitState.OPEN) {
            currentSource = fallbackAvailable ? "fallback" : "none";
        } else {
            currentSource = primaryAvailable ? "primary" : (fallbackAvailable ? "fallback" : "none");
        }

        return new ApiSourceStatus(
                currentSource,
                primaryAvailable,
                fallbackAvailable,
                stats
        );
    }

    /**
     * API源状态信息
     */
    public record ApiSourceStatus(
            String currentSource,        // 当前使用的源: primary, fallback, none
            boolean primaryAvailable,    // 主API是否可用
            boolean fallbackAvailable,   // 备用API是否可用
            CircuitBreaker.CircuitStats circuitStats  // 熔断器统计
    ) {}
}
