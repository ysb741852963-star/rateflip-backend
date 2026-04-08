package com.rateflip.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器实现
 * 用于在主API故障时自动切换到备用API，并在主API恢复后自动切回
 */
@Component
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    // 熔断器配置
    private final int failureThreshold;          // 连续失败次数阈值，达到后触发熔断
    private final long openTimeoutMillis;        // 熔断持续时间
    private final int successThreshold;          // 半开状态下，连续成功次数阈值，达到后关闭熔断

    // 状态管理
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    // 统计信息
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);
    private final AtomicLong totalSuccesses = new AtomicLong(0);
    private final AtomicLong circuitOpenedCount = new AtomicLong(0);

    public CircuitBreaker(
            @Value("${circuitbreaker.failure-threshold:3}") int failureThreshold,
            @Value("${circuitbreaker.open-timeout-millis:30000}") long openTimeoutMillis,
            @Value("${circuitbreaker.success-threshold:2}") int successThreshold) {
        this.failureThreshold = failureThreshold;
        this.openTimeoutMillis = openTimeoutMillis;
        this.successThreshold = successThreshold;
        logger.info("熔断器初始化: 失败阈值={}, 熔断持续时间={}ms, 恢复成功阈值={}",
                failureThreshold, openTimeoutMillis, successThreshold);
    }

    /**
     * 记录成功调用
     */
    public void recordSuccess() {
        totalRequests.incrementAndGet();
        totalSuccesses.incrementAndGet();

        CircuitState currentState = state.get();
        
        if (currentState == CircuitState.HALF_OPEN) {
            int successes = consecutiveSuccesses.incrementAndGet();
            logger.debug("半开状态连续成功次数: {}/{}", successes, successThreshold);
            
            if (successes >= successThreshold) {
                closeCircuit();
            }
        } else if (currentState == CircuitState.CLOSED) {
            // 成功后重置连续失败计数
            consecutiveFailures.set(0);
        }
    }

    /**
     * 记录失败调用
     */
    public void recordFailure() {
        totalRequests.incrementAndGet();
        totalFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        CircuitState currentState = state.get();
        
        if (currentState == CircuitState.HALF_OPEN) {
            // 半开状态失败，立即重新打开熔断器
            logger.warn("半开状态探测失败，重新打开熔断器");
            openCircuit();
        } else if (currentState == CircuitState.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            consecutiveSuccesses.set(0);
            logger.debug("连续失败次数: {}/{}", failures, failureThreshold);
            
            if (failures >= failureThreshold) {
                openCircuit();
            }
        }
    }

    /**
     * 检查熔断器是否允许请求通过
     */
    public boolean allowRequest() {
        CircuitState currentState = state.get();
        
        if (currentState == CircuitState.CLOSED) {
            return true;
        }
        
        if (currentState == CircuitState.OPEN) {
            // 检查是否超时，可以转换为半开状态
            long elapsed = System.currentTimeMillis() - lastFailureTime.get();
            if (elapsed >= openTimeoutMillis) {
                logger.info("熔断超时({}ms)，切换到半开状态进行探测", elapsed);
                state.set(CircuitState.HALF_OPEN);
                consecutiveSuccesses.set(0);
                return true;
            }
            return false;
        }
        
        // HALF_OPEN 状态允许请求通过进行探测
        return true;
    }

    /**
     * 获取当前状态
     */
    public CircuitState getState() {
        return state.get();
    }

    /**
     * 获取当前连续失败次数
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 获取熔断器统计信息
     */
    public CircuitStats getStats() {
        return new CircuitStats(
                state.get(),
                consecutiveFailures.get(),
                consecutiveSuccesses.get(),
                totalRequests.get(),
                totalSuccesses.get(),
                totalFailures.get(),
                circuitOpenedCount.get()
        );
    }

    /**
     * 强制关闭熔断器（用于测试或手动恢复）
     */
    public void reset() {
        state.set(CircuitState.CLOSED);
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);
        logger.info("熔断器已重置为关闭状态");
    }

    /**
     * 强制打开熔断器（用于测试或紧急熔断）
     */
    public void forceOpen() {
        openCircuit();
    }

    private void openCircuit() {
        state.set(CircuitState.OPEN);
        lastFailureTime.set(System.currentTimeMillis());
        circuitOpenedCount.incrementAndGet();
        consecutiveFailures.set(0);
        logger.warn("熔断器已打开，API调用将被阻断 {}ms", openTimeoutMillis);
    }

    private void closeCircuit() {
        state.set(CircuitState.CLOSED);
        consecutiveFailures.set(0);
        consecutiveSuccesses.set(0);
        logger.info("熔断器已关闭，API恢复正常");
    }

    /**
     * 熔断器统计信息
     */
    public record CircuitStats(
            CircuitState state,
            int consecutiveFailures,
            int consecutiveSuccesses,
            long totalRequests,
            long totalSuccesses,
            long totalFailures,
            long circuitOpenedCount
    ) {}
}
