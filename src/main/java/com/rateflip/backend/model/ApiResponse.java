package com.rateflip.backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 通用 API 响应封装
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class ApiResponse<T> {
    
    private Boolean success;      // 是否成功
    private String error;         // 错误信息
    private T data;              // 响应数据

    public ApiResponse() {
    }

    public ApiResponse(Boolean success, T data) {
        this.success = success;
        this.data = data;
    }

    public ApiResponse(Boolean success, String error) {
        this.success = success;
        this.error = error;
    }

    /**
     * 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data);
    }

    /**
     * 失败响应
     */
    public static <T> ApiResponse<T> fail(String error) {
        return new ApiResponse<>(false, error);
    }

}
