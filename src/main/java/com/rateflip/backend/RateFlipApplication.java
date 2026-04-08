package com.rateflip.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RateFlip 后端应用启动类
 * 汇率 API 服务（带 Redis 缓存）
 */
@SpringBootApplication
public class RateFlipApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateFlipApplication.class, args);
    }
}
