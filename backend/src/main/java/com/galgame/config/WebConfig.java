package com.galgame.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.galgame.auth.AuthInterceptor;

/**
 * 全局配置：CORS、/uploads/** 静态资源映射、登录鉴权拦截器。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
        // 附件也需要被前端（如 canvas 读图）跨域访问
        registry.addMapping("/uploads/**")
                .allowedOrigins("http://localhost:5173", "http://127.0.0.1:5173")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 相对路径 file:uploads/ 以进程工作目录为基准（java -jar 在 backend/ 下运行时即 backend/uploads/）
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 需登录的接口；GET /api/posts（列表）在拦截器内放行
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(
                        "/api/auth/logout",
                        "/api/auth/me",
                        "/api/auth/profile",
                        "/api/auth/avatar",
                        "/api/auth/admin-verify",
                        "/api/auth/password",
                        "/api/posts",
                        "/api/posts/*/replies",
                        "/api/posts/*/like",
                        "/api/posts/*/favorite",
                        "/api/replies/*/like",
                        "/api/replies/*",
                        "/api/notifications/**",
                        "/api/announcements",
                        "/api/posts/*/category",
                        "/api/posts/*/report",
                        "/api/replies/*/report",
                        "/api/reports/**",
                        "/api/users/*/unban");
    }
}
