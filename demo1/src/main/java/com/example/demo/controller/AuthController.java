package com.example.demo.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.example.demo.auth.TokenService;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

/**
 * 登录 / 注册，以及登录令牌的签发。
 *
 * <p>登录成功后返回 {@code token}，前端把它放进 {@code Authorization: Bearer}，
 * 之后所有需要身份的接口都由服务端从 token 解析调用方，不再信任请求体里的 userId。</p>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    // 登录
    @PostMapping("/login")
    @Transactional
    public ResponseEntity<Map<String, Object>> login(@RequestParam String stuid,
                                                     @RequestParam String password) {
        User user = userRepository.findByStuid(stuid);
        Map<String, Object> body = new LinkedHashMap<>();
        if (user == null || !passwordMatches(password, user)) {
            // 不区分「学号不存在」与「密码错误」：区分开就成了账号枚举接口
            body.put("success", false);
            body.put("msg", "学号或密码错误");
            return ResponseEntity.ok(body);
        }
        body.put("success", true);
        body.put("user", JSON.parse(JSON.toJSONString(user)));
        body.put("token", tokenService.issue(user.getId(), user.getRole()));
        return ResponseEntity.ok(body);
    }

    /**
     * 校验密码，并顺手把历史明文密码升级成 BCrypt。
     *
     * <p>原来密码是明文存库，现存的库和从旧备份恢复的数据里都还是明文；全量迁移要额外
     * 写脚本。这里做「登录时惰性升级」：命中明文的老账号在首次登录成功后自动换成哈希，
     * 之后明文即失效。新注册的账号直接就是哈希。</p>
     */
    private boolean passwordMatches(String raw, User user) {
        String stored = user.getPassword();
        if (stored == null) {
            return false;
        }
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            return passwordEncoder.matches(raw, stored);
        }
        if (!stored.equals(raw)) {
            return false;
        }
        user.setPassword(passwordEncoder.encode(raw));
        userRepository.save(user);
        log.info("账号 {} 的明文密码已在登录时升级为 BCrypt", user.getStuid());
        return true;
    }

    // 注册
    @PostMapping("/register")
    @Transactional
    public ResponseEntity<Map<String, Object>> register(@RequestParam String username,
                                                       @RequestParam String stuid,
                                                       @RequestParam String password) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            // 检查学号是否已存在
            if (userRepository.findByStuid(stuid) != null) {
                body.put("success", false);
                body.put("message", "学号已被注册");
                return ResponseEntity.ok(body);
            }

            User user = new User();
            user.setUsername(username);
            user.setStuid(stuid);
            user.setPassword(passwordEncoder.encode(password));
            user = userRepository.save(user);

            body.put("success", true);
            body.put("userId", user.getId());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // 不要把 e.getMessage() 回给客户端：Hibernate 的异常文本里带表名/列名，
            // 有时还带 SQL 片段，等于免费给攻击者一份表结构。
            log.warn("注册失败 stuid={}", stuid, e);
            body.put("success", false);
            body.put("message", "注册失败，请稍后重试");
            return ResponseEntity.badRequest().body(body);
        }
    }
}
