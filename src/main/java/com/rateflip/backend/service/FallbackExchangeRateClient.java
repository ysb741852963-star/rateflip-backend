package com.rateflip.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rateflip.backend.model.ExchangeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 备用汇率API客户端
 * 当主API不可用时，使用此备用接口获取汇率数据
 * 
 * 支持的备用API源:
 * - exchangerate-api.com (免费层有限制)
 * - frankfurter.app (开源免费，无API Key)
 */
@Service
public class FallbackExchangeRateClient {

    private static final Logger logger = LoggerFactory.getLogger(FallbackExchangeRateClient.class);

    @Value("${exchange.api.fallback.base-url:https://api.frankfurter.app}")
    private String fallbackBaseUrl;

    @Value("${exchange.api.fallback.endpoint:/v1/latest}")
    private String fallbackEndpoint;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public FallbackExchangeRateClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 从备用API获取汇率数据
     * @param baseCurrency 基准货币代码（例如："USD"）
     * @return ExchangeRate 汇率对象
     * @throws Exception 如果 API 调用失败
     */
    public ExchangeRate fetchRates(String baseCurrency) throws Exception {
        String url = fallbackBaseUrl + fallbackEndpoint + "?from=" + baseCurrency.toUpperCase();
        logger.info("从备用API获取汇率: {}", url);

        try {
            String response = restTemplate.getForObject(url, String.class);
            logger.debug("备用API响应: {}", response);

            JsonNode root = objectMapper.readTree(response);

            // 检查API返回结果
            if (root.has("error")) {
                throw new Exception("备用API返回错误: " + root.path("error").asText());
            }

            String resultBase = root.path("base").asText();
            
            // frankfurter API 使用 "date" 而不是 timestamp
            String date = root.path("date").asText();
            long timestamp = System.currentTimeMillis() / 1000; // 使用当前时间戳
            
            Map<String, Double> rates = new HashMap<>();
            JsonNode ratesNode = root.path("rates");
            ratesNode.fields().forEachRemaining(entry -> {
                rates.put(entry.getKey(), entry.getValue().asDouble());
            });

            ExchangeRate exchangeRate = new ExchangeRate();
            exchangeRate.setBase(resultBase);
            exchangeRate.setTimestamp(timestamp);
            exchangeRate.setRates(rates);
            exchangeRate.setCached(false);
            exchangeRate.setFallbackSource(true); // 标记来自备用源

            logger.info("备用API获取到 {} 个货币汇率，基准货币: {}", rates.size(), resultBase);
            return exchangeRate;

        } catch (Exception e) {
            logger.error("备用API调用失败: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * 检查备用API是否可用
     * @return true 如果可用
     */
    public boolean isAvailable() {
        try {
            String url = fallbackBaseUrl + "/latest?from=USD";
            String forObject = restTemplate.getForObject(url, String.class);
            return true;
        } catch (Exception e) {
            logger.warn("备用API不可用: {}", e.getMessage());
            return false;
        }
    }
}
