package com.example.demo.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

/**
 * 登录 / 注册。
 *
 * <p>原 {@code ApiController} 把 {@code /login} 和 {@code /register} 也都塞在一起，
 * 这两个端点是 form 参数（{@code @RequestParam}）而非 JSON，与商品/用户管理的风格不同，
 * 拆开之后阅读更聚焦。</p>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    @Autowired
    private UserRepository userRepository;

    // 登录
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestParam String stuid,
                                                     @RequestParam String password) {
        User user = userRepository.findByStuidAndPassword(stuid, password);
        Map<String, Object> body = new LinkedHashMap<>();
        if (user != null) {
            body.put("success", true);
            body.put("user", JSON.parse(JSON.toJSONString(user)));
        } else {
            body.put("success", false);
            body.put("msg", "学号或密码错误");
        }
        return ResponseEntity.ok(body);
    }

    // 注册
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<Map<String, Object>> register(@RequestParam String username,
                                                       @RequestParam String stuid,
                                                       @RequestParam String password) {
        try {
            // 检查学号是否已存在
            User existingUser = userRepository.findByStuid(stuid);
            if (existingUser != null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("message", "学号已被注册");
                return ResponseEntity.ok(body);
            }

            // 创建新用户
            User user = new User();
            user.setUsername(username);
            user.setStuid(stuid);
            user.setPassword(password);
            user = userRepository.save(user);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("userId", user.getId());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(body);
        }
    }
}
