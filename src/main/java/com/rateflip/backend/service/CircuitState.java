package com.rateflip.backend.service;

/**
 * 熔断器状态枚举
 */
public enum CircuitState {
    CLOSED,     // 正常状态，流量通过
    OPEN,       // 熔断状态，流量阻断
    HALF_OPEN   // 半开状态，允许探测性请求
}
