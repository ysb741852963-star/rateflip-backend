package com.rateflip.backend.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 汇率数据模型
 * @author Beal
 */
@Data
public class ExchangeRate implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private String base;                      // 基准货币代码
    private Long timestamp;                   // 更新时间戳
    private Map<String, Double> rates;       // 汇率映射表
    private Boolean cached;                   // 是否来自缓存
    private Integer cacheAgeSeconds;          // 缓存年龄（秒）
    private Boolean stale;                    // 是否为过期缓存
    private Boolean fallbackSource;           // 是否来自备用数据源

    public ExchangeRate() {
    }

    public ExchangeRate(String base, Long timestamp, Map<String, Double> rates, Boolean cached) {
        this.base = base;
        this.timestamp = timestamp;
        this.rates = rates;
        this.cached = cached;
    }


}
