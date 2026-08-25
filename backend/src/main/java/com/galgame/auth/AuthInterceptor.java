package com.galgame.auth;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录鉴权拦截器：解析 <code>Authorization: Bearer &lt;token&gt;</code>，
 * 校验通过后把当前用户 id 放入 request attribute。
 * <p>未登录 / token 无效 / 已过期时统一返回 401 <code>{"error":"请先登录。"}</code>。
 * <p>注意：GET /api/posts（列表）是公开接口，注册到该路径但只拦截 POST（发帖）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;

    public AuthInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // CORS 预检放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // /api/posts 同时承载 GET(列表,公开) 与 POST(发帖,需登录)
        if ("/api/posts".equals(request.getRequestURI()) && !"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Optional<Long> userId = tokenService.resolveUserId(request.getHeader("Authorization"));
        if (userId.isPresent()) {
            request.setAttribute(AuthContext.CURRENT_USER_ID, userId.get());
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"请先登录。\"}");
        return false;
    }
}
