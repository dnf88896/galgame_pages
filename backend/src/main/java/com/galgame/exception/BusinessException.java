package com.galgame.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务层异常：Service 层用状态码 + 消息表达业务失败（不存在 / 无权限 / 参数非法等），
 * Controller 捕获后统一转为 {@code ResponseEntity.status(status).body(Map.of("error", message))}。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
