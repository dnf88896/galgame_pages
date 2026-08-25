package com.galgame.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前登录用户上下文：由 {@link AuthInterceptor} 把用户 id 放入 request attribute，
 * 受保护接口通过 {@link #currentUserId(HttpServletRequest)} 读取。
 */
public final class AuthContext {

    public static final String CURRENT_USER_ID = "currentUserId";

    private AuthContext() {
    }

    /** 读取当前登录用户 id；仅可在受鉴权拦截器保护的接口中调用 */
    public static long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(CURRENT_USER_ID);
        if (value instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("缺少登录用户上下文");
    }
}
