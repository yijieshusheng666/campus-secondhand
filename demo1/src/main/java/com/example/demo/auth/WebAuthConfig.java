package com.example.demo.auth;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器，并顺带把 CORS 收紧。
 *
 * <p>原来的 CORS 是 {@code allowedOriginPatterns("*") + allowCredentials(true)}，
 * 等价于「任何站点都能带凭证跨域调用你所有接口」，而且 {@code *} 还把 PUT/DELETE
 * 一起放开了 —— 任意第三方页面都能静默发起删除请求。现在改成显式白名单，
 * 并且不再允许携带凭证（身份走请求头里的 token，不需要 Cookie）。</p>
 */
@Configuration
public class WebAuthConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 逗号分隔的允许来源；默认只放本机 Vite dev server。 */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String allowedOrigins;

    public WebAuthConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addMapping("/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
