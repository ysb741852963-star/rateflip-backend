package com.rateflip.backend;

import com.rateflip.backend.model.ExchangeRate;
import com.rateflip.backend.service.ExchangeRateService;
import com.rateflip.backend.service.FallbackExchangeRateClient;
import com.rateflip.backend.service.ExternalApiClient;
import com.rateflip.backend.service.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 统一缓存逻辑验证测试
 * 验证：所有货币请求都只调用一次API(USD)，其他货币通过交叉计算得出
 */
public class UnifiedCacheVerificationTest {

    private ExchangeRateService service;
    private ExternalApiClient externalApiClient;
    private FallbackExchangeRateClient fallbackClient;
    private CircuitBreaker circuitBreaker;
    private org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private org.springframework.data.redis.core.ValueOperations<String, Object> valueOperations;

    @BeforeEach
    void setUp() throws Exception {
        externalApiClient = mock(ExternalApiClient.class);
        fallbackClient = mock(FallbackExchangeRateClient.class);
        circuitBreaker = new CircuitBreaker(3, 30000, 2);
        redisTemplate = mock(org.springframework.data.redis.core.RedisTemplate.class);
        valueOperations = mock(org.springframework.data.redis.core.ValueOperations.class);
        
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        
        service = new ExchangeRateService(
                redisTemplate,
                externalApiClient,
                fallbackClient,
                circuitBreaker
        );
        
        // 反射设置 cacheTtlSeconds = 300
        Field field = ExchangeRateService.class.getDeclaredField("cacheTtlSeconds");
        field.setAccessible(true);
        field.setLong(service, 300L);
    }

    /**
     * 构建假的 USD 汇率数据
     * usdRates["USD"] = 1.0, usdRates["EUR"] = 0.92, usdRates["GBP"] = 0.79, usdRates["JPY"] = 149.5
     * 表示：1 USD = 0.92 EUR, 1 USD = 0.79 GBP, 1 USD = 149.5 JPY
     */
    private ExchangeRate buildFakeUsdRates() {
        Map<String, Double> rates = new HashMap<>();
        rates.put("USD", 1.0);
        rates.put("EUR", 0.92);
        rates.put("GBP", 0.79);
        rates.put("JPY", 149.5);
        
        ExchangeRate rate = new ExchangeRate();
        rate.setBase("USD");
        rate.setTimestamp(System.currentTimeMillis() / 1000);
        rate.setRates(rates);
        rate.setCached(false);
        rate.setFallbackSource(false);
        return rate;
    }

    @Test
    @DisplayName("场景1: USD缓存不存在，请求EUR - 应调用一次API(USD)，交叉汇率计算正确")
    void requestEur_cacheMiss_shouldCallApiOnceAndComputeCrossRate() throws Exception {
        // Arrange: Redis 中没有缓存
        when(valueOperations.get("rateflip:rates:USD")).thenReturn(null);
        
        ExchangeRate fakeUsdRates = buildFakeUsdRates();
        when(externalApiClient.fetchRates("USD")).thenReturn(fakeUsdRates);

        // Act: 请求 EUR
        ExchangeRate result = service.getRates("EUR");

        // Assert 1: 只调用了一次 API，且参数为 "USD"
        ArgumentCaptor<String> apiCaptor = ArgumentCaptor.forClass(String.class);
        verify(externalApiClient, times(1)).fetchRates(apiCaptor.capture());
        assertEquals("USD", apiCaptor.getValue(), "API应使用USD作为参数");
        
        // Assert 2: 返回结果的 base 是 EUR
        assertEquals("EUR", result.getBase(), "返回汇率的base应为EUR");
        
        // Assert 3: EUR 自身的汇率是 1.0
        assertEquals(1.0, result.getRates().get("EUR"), 0.0001, "EUR自身汇率为1.0");
        
        // Assert 4: USD 的汇率 = 1/0.92 ≈ 1.087 (1 EUR = 1.087 USD)
        assertEquals(1.0 / 0.92, result.getRates().get("USD"), 0.0001);
        
        // Assert 5: GBP 的汇率 = 0.79/0.92 ≈ 0.859 (1 EUR = 0.859 GBP)
        assertEquals(0.79 / 0.92, result.getRates().get("GBP"), 0.0001);
    }

    @Test
    @DisplayName("场景2: USD缓存已存在，请求GBP - 不调用API，直接计算")
    void requestGbp_cacheHit_shouldNotCallApi() throws Exception {
        // Arrange
        ExchangeRate cachedUsdRates = buildFakeUsdRates();
        when(valueOperations.get("rateflip:rates:USD")).thenReturn(cachedUsdRates);

        // Act
        ExchangeRate result = service.getRates("GBP");

        // Assert 1: 没有调用任何 API
        verify(externalApiClient, never()).fetchRates(anyString());
        verify(fallbackClient, never()).fetchRates(anyString());
        
        // Assert 2: 返回结果的 base 是 GBP
        assertEquals("GBP", result.getBase());
        
        // Assert 3: GBP 自身的汇率是 1.0
        assertEquals(1.0, result.getRates().get("GBP"), 0.0001);
        
        // Assert 4: EUR 的汇率 = 0.92/0.79 ≈ 1.165 (1 GBP = 1.165 EUR)
        assertEquals(0.92 / 0.79, result.getRates().get("EUR"), 0.0001);
        
        // Assert 5: USD 的汇率 = 1/0.79 ≈ 1.266 (1 GBP = 1.266 USD)
        assertEquals(1.0 / 0.79, result.getRates().get("USD"), 0.0001);
    }

    @Test
    @DisplayName("场景3: 连续请求EUR和GBP - 只调用一次API")
    void requestEurThenGbp_shouldCallApiOnlyOnce() throws Exception {
        // Arrange: 第一次请求时没有缓存，第二次请求时从缓存获取
        ExchangeRate fakeUsdRates = buildFakeUsdRates();
        
        // 第一次 get 返回 null（触发API调用），第二次返回缓存
        when(valueOperations.get("rateflip:rates:USD"))
                .thenReturn(null)           
                .thenReturn(fakeUsdRates);  
        
        when(externalApiClient.fetchRates("USD")).thenReturn(fakeUsdRates);

        // Act
        service.getRates("EUR");
        service.getRates("GBP");

        // Assert: 整个过程中只调用了一次 API
        verify(externalApiClient, times(1)).fetchRates("USD");
    }

    @Test
    @DisplayName("场景4: 请求USD - 直接返回缓存")
    void requestUsd_shouldReturnDirectly() throws Exception {
        // Arrange
        ExchangeRate cachedUsdRates = buildFakeUsdRates();
        when(valueOperations.get("rateflip:rates:USD")).thenReturn(cachedUsdRates);

        // Act
        ExchangeRate result = service.getRates("USD");

        // Assert
        verify(externalApiClient, never()).fetchRates(anyString());
        assertEquals("USD", result.getBase());
        assertEquals(1.0, result.getRates().get("USD"), 0.0001);
        assertEquals(0.92, result.getRates().get("EUR"), 0.0001);
    }

    @Test
    @DisplayName("场景5: 验证Redis缓存键统一为 rateflip:rates:USD")
    void verifyCacheKey_shouldAlwaysBeUsd() throws Exception {
        // Arrange
        when(valueOperations.get("rateflip:rates:USD")).thenReturn(null);
        ExchangeRate fakeUsdRates = buildFakeUsdRates();
        when(externalApiClient.fetchRates("USD")).thenReturn(fakeUsdRates);

        // Act: 请求 JPY
        service.getRates("JPY");

        // Assert: 缓存写入键为 rateflip:rates:USD
        verify(valueOperations).set(
                eq("rateflip:rates:USD"),
                any(ExchangeRate.class),
                anyLong(),
                any()
        );
    }
}
