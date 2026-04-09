package com.rateflip.backend.controller;

import com.rateflip.backend.model.ApiResponse;
import com.rateflip.backend.model.ExchangeRate;
import com.rateflip.backend.service.ExchangeRateService;
import com.rateflip.backend.service.ExchangeRateService.DailyStats;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * 汇率 API 控制器
 */
@RestController
@RequestMapping("/api/v1")
public class ExchangeRateController {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateController.class);

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    /**
     * 获取汇率
     * GET /api/v1/rates?base=USD
     * 
     * @param base 基准货币代码（默认 USD）
     * @return 汇率数据（含缓存信息）
     */
    @GetMapping("/rates")
    public ResponseEntity<ApiResponse<ExchangeRate>> getRates(
            @RequestParam(defaultValue = "USD") String base) {
        try {
            ExchangeRate rates = exchangeRateService.getRates(base.toUpperCase());
            return ResponseEntity.ok(ApiResponse.success(rates));
        } catch (Exception e) {
            logger.error("获取汇率出错: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail(e.getMessage()));
        }
    }

    /**
     * 强制刷新汇率
     * GET /api/v1/rates/refresh?base=USD
     * 
     * @param base 基准货币代码（默认 USD）
     * @return 最新汇率数据
     */
    @GetMapping("/rates/refresh")
    public ResponseEntity<ApiResponse<ExchangeRate>> refreshRates(
            @RequestParam(defaultValue = "USD") String base) {
        try {
            ExchangeRate rates = exchangeRateService.refreshRates(base.toUpperCase());
            return ResponseEntity.ok(ApiResponse.success(rates));
        } catch (Exception e) {
            logger.error("刷新汇率出错: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail(e.getMessage()));
        }
    }

    /**
     * 检查缓存状态（用于"已是最新"功能）
     * GET /api/v1/rates/status?base=USD
     * 
     * @param base 基准货币代码
     * @return 缓存状态
     */
    @GetMapping("/rates/status")
    public ResponseEntity<ApiResponse<CacheStatus>> getCacheStatus(
            @RequestParam(defaultValue = "USD") String base) {
        boolean fresh = exchangeRateService.isCacheFresh(base.toUpperCase());
        CacheStatus status = new CacheStatus(base.toUpperCase(), fresh);
        
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * 健康检查
     * GET /api/v1/health
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("RateFlip API 运行正常！"));
    }

    /**
     * 获取每日统计（请求数、活跃货币对）
     * GET /api/v1/rates/stats/daily?date=20260409
     *
     * @param date 日期（yyyyMMdd格式，不传默认当天）
     * @return 每日统计数据
     */
    @GetMapping("/rates/stats/daily")
    public ResponseEntity<ApiResponse<DailyStats>> getDailyStats(
            @RequestParam(required = false) String date) {
        try {
            DailyStats stats = exchangeRateService.getDailyStats(date);
            return ResponseEntity.ok(ApiResponse.success(stats));
        } catch (Exception e) {
            logger.error("获取每日统计出错: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.fail(e.getMessage()));
        }
    }

    /**
     * 缓存状态模型
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CacheStatus {
        private String base;
        private boolean fresh;
    }
}
