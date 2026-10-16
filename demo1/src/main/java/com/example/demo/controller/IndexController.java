package com.example.demo.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 根路径的健康检查/版本号端点。
 *
 * <p>原来的 {@code ApiController} 同时塞了商品 CRUD、登录注册、用户管理、推荐，
 * 上百行的 {@code @RequestMapping("/api")} 拆开后，根路径只剩下这一行健康检查。</p>
 *
 * <p>⚠️ 类级 {@code @RequestMapping("/api")} 不能省：拆分类的时候漏过一次，
 * {@code @GetMapping("")} 于是落在了根路径 {@code /}，README 里写的启动自检
 * {@code curl http://localhost:8080/api} 直接 404。</p>
 */
@RestController
@RequestMapping("/api")
public class IndexController {

    @GetMapping("")
    public ResponseEntity<Map<String, Object>> index() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "API 服务运行正常");
        body.put("version", "2.0.0");
        return ResponseEntity.ok(body);
    }
}
