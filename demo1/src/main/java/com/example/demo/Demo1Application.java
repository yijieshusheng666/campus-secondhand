package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.demo.web.UploadPaths;

@SpringBootApplication
public class Demo1Application {

    private static final Logger log = LoggerFactory.getLogger(Demo1Application.class);

    public static void main(String[] args) {
        SpringApplication.run(Demo1Application.class, args);
        // 把上传目录的绝对路径打进日志：图片 404 时不用再靠猜。
        log.info("商品图片上传目录：{}", UploadPaths.dir().getAbsolutePath());
    }

    /**
     * 静态资源映射。
     *
     * <p>CORS 与鉴权拦截器都挪到了 {@code com.example.demo.auth.WebAuthConfig}：
     * 原来的 CORS 是 {@code allowedOriginPatterns("*") + allowCredentials(true)}，
     * 等于允许任何站点带凭证跨域调所有接口，和鉴权放在一起更好对照着看。</p>
     */
    @Bean
    public WebMvcConfigurer webConfigurer() {
        return new WebMvcConfigurer() {
            // 将本地上传目录映射为静态资源，供前端展示商品图片
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                // 和 GoodsController 写入时用同一个路径解析，避免两边各算各的
                registry.addResourceHandler("/uploads/**")
                        .addResourceLocations(UploadPaths.dir().toURI().toString());
            }
        };
    }

}