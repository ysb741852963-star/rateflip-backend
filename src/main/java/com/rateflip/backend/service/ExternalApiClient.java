package com.rateflip.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rateflip.backend.model.ExchangeRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/**
 * 主API客户端
 * 负责从 open.er-api.com 获取汇率数据
 * 集成熔断器机制
 */
@Service
public class ExternalApiClient {

    private static final Logger logger = LoggerFactory.getLogger(ExternalApiClient.class);

    @Value("${exchange.api.base-url}")
    private String baseUrl;

    @Value("${exchange.api.endpoint}")
    private String endpoint;

    @Value("${exchange.api.timeout:10000}")
    private int timeout;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;

    public ExternalApiClient(
            @Autowired(required = false) CircuitBreaker circuitBreaker) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        // 如果没有配置熔断器，创建一个默认的
        this.circuitBreaker = circuitBreaker != null ? circuitBreaker : new CircuitBreaker(3, 30000, 2);
    }

    /**
     * 从外部API获取汇率数据（带熔断器保护）
     * @param baseCurrency 基准货币代码（例如："USD"）
     * @return ExchangeRate 汇率对象
     * @throws ApiException 如果 API 调用失败或熔断器打开
     */
    public ExchangeRate fetchRates(String baseCurrency) throws ApiException {
        // 检查熔断器是否允许请求
        if (!circuitBreaker.allowRequest()) {
            throw new ApiException("主API熔断器已打开，请使用备用API", ApiException.Type.CIRCUIT_OPEN);
        }

        String url = baseUrl + endpoint + "/" + baseCurrency;
        logger.info("从主API获取汇率: {}", url);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            
            ExchangeRate exchangeRate = parseResponse(response.getBody(), baseCurrency);
            circuitBreaker.recordSuccess();
            return exchangeRate;

        } catch (HttpServerErrorException e) {
            // 5xx 错误
            handleHttpError(e, "Server Error");
            throw new ApiException("主API服务器错误: " + e.getStatusCode(), ApiException.Type.SERVER_ERROR, e);

        } catch (HttpClientErrorException e) {
            // 429 Too Many Requests
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                handleHttpError(e, "Rate Limited");
                throw new ApiException("主API请求频率超限", ApiException.Type.RATE_LIMITED, e);
            }
            // 4xx 其他客户端错误
            handleHttpError(e, "Client Error");
            throw new ApiException("主API客户端错误: " + e.getStatusCode(), ApiException.Type.CLIENT_ERROR, e);

        } catch (ResourceAccessException e) {
            handleError(e, "Timeout");
            throw new ApiException("主API请求超时", ApiException.Type.TIMEOUT, e);

        } catch (Exception e) {
            handleError(e, "Unknown");
            throw new ApiException("主API调用失败: " + e.getMessage(), ApiException.Type.UNKNOWN, e);
        }
    }

    /**
     * 检查主API是否可用（不记录熔断器状态）
     */
    public boolean isAvailable() {
        try {
            String url = baseUrl + endpoint + "/USD";
            restTemplate.getForObject(url, String.class);
            return true;
        } catch (Exception e) {
            logger.warn("主API不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取熔断器状态
     */
    public CircuitBreaker.CircuitStats getCircuitBreakerStats() {
        return circuitBreaker.getStats();
    }

    private ExchangeRate parseResponse(String responseBody, String baseCurrency) throws ApiException {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 检查 API 返回结果（注意：API 返回的是 "success" 不是 "ok"）
            if (!"success".equals(root.path("result").asText())) {
                String errorType = root.path("error-type").asText("未知错误");
                throw new ApiException("API 返回错误: " + errorType, ApiException.Type.API_ERROR);
            }

            String resultBase = root.path("base_code").asText();
            long timestamp = root.path("time_last_update_unix").asLong();

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
            exchangeRate.setFallbackSource(false);

            logger.info("主API获取到 {} 个货币汇率，基准货币: {}", rates.size(), resultBase);
            return exchangeRate;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("解析API响应失败: " + e.getMessage(), ApiException.Type.PARSE_ERROR, e);
        }
    }

    private void handleHttpError(Exception e, String type) {
        logger.error("主API HTTP错误 ({}): {}", type, e.getMessage());
        circuitBreaker.recordFailure();
    }

    private void handleError(Exception e, String type) {
        logger.error("主API调用错误 ({}): {}", type, e.getMessage());
        circuitBreaker.recordFailure();
    }

    /**
     * API异常封装
     */
    public static class ApiException extends Exception {
        public enum Type {
            CIRCUIT_OPEN,    // 熔断器打开
            SERVER_ERROR,    // 5xx 服务器错误
            CLIENT_ERROR,    // 4xx 客户端错误
            RATE_LIMITED,    // 429 请求频率超限
            TIMEOUT,         // 请求超时
            API_ERROR,       // API返回错误
            PARSE_ERROR,     // 解析错误
            UNKNOWN          // 未知错误
        }

        private final Type type;

        public ApiException(String message, Type type) {
            super(message);
            this.type = type;
        }

        public ApiException(String message, Type type, Throwable cause) {
            super(message, cause);
            this.type = type;
        }

        public Type getType() {
            return type;
        }

        public boolean isRetryable() {
            return type == Type.SERVER_ERROR || type == Type.TIMEOUT || type == Type.RATE_LIMITED;
        }
    }
}
