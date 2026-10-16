package com.example.demo.controller;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.AuthContext;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CreditService;

/**
 * 用户资料查询、昵称 / 头像更新、学号实名认证。
 *
 * <p>{@code /users/{id}} 的响应是「字段平铺」的——前端很多地方直接读
 * {@code data.username / data.role / data.avatar}，这里维持原样（不嵌套在
 * {@code data} 对象里）。</p>
 */
@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CreditService creditService;

    /**
     * 内置头像白名单：头像是「只能在这几张之间换」的，
     * 前端只给这 4 个选项，后端这里再挡一次，避免被传任意字符串或外链。
     * 新增头像时两边要一起改（前端 frontend/src/avatars.js）。
     */
    private static final Set<String> AVATARS = Set.of("avatar-1", "avatar-2", "avatar-3", "avatar-4");

    // 查询用户基本信息（卖家昵称展示、沟通对象展示用）
    @GetMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("stuid", user.getStuid() == null ? "" : user.getStuid());
        // 头像：内置头像的 key（avatar-1..4），没选过返回 null，前端回退成昵称首字
        body.put("avatar", user.getAvatar());
        // B1：只对外暴露"是否已实名"和脱敏姓名，不返回完整真实姓名
        body.put("verified", Boolean.TRUE.equals(user.getVerified()));
        body.put("realNameMasked", maskName(user.getRealName()));
        // B3：信用分与评价概览，供商品详情展示卖家信誉
        body.putAll(creditService.summaryOf(user.getId()));
        // B4：管理端入口判断用（user / admin）
        body.put("role", user.getRole());
        return ResponseEntity.ok(body);
    }

    // 修改用户昵称 / 头像（两者都可以单独提交）。被改的用户取自 token：
    // 从前读 body 里的 id，等于可以改任意人的昵称和头像
    @PostMapping("/user/update")
    @Transactional
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        Long id = AuthContext.requireUserId();
        String username = body.get("username") == null ? null : body.get("username").toString();
        String avatar = body.get("avatar") == null ? null : body.get("avatar").toString();
        boolean changeName = username != null && !username.isBlank();
        if (!changeName && avatar == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "没有要修改的内容");
            return ResponseEntity.badRequest().body(err);
        }
        if (avatar != null && !AVATARS.contains(avatar)) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "不支持的头像");
            return ResponseEntity.badRequest().body(err);
        }
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("message", "用户不存在");
            return ResponseEntity.badRequest().body(err);
        }
        if (changeName) {
            user.setUsername(username.trim());
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userRepository.save(user);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        ok.put("message", changeName ? "昵称修改成功" : "头像已更换");
        return ResponseEntity.ok(ok);
    }

    /**
     * B1 学号实名认证。
     * 校验学号格式 + 中文姓名 + 学号唯一，通过后写入真实姓名并标记已认证。
     * 说明：这里只做格式与唯一性校验，真正的实名核验需要对接学校教务系统。
     */
    @PostMapping("/user/verify")
    @Transactional
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body) {
        // 认证对象取自 token：从前读 body 里的 id，等于可以把别人的账号标成已实名
        Long id = AuthContext.requireUserId();
        User user = userRepository.findById(id).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(verifyFail("用户不存在"));
        }
        if (Boolean.TRUE.equals(user.getVerified())) {
            return ResponseEntity.ok(verifyOk(user, "该账号已完成实名认证"));
        }

        String stuid = user.getStuid() == null ? "" : user.getStuid().trim();
        if (!stuid.matches("^[A-Za-z0-9]{4,20}$")) {
            return ResponseEntity.badRequest().body(verifyFail("学号格式不正确（4-20 位字母或数字）"));
        }

        String realName = body.get("realName") == null ? "" : body.get("realName").toString().trim();
        if (!realName.matches("^[\\u4e00-\\u9fa5·]{2,10}$")) {
            return ResponseEntity.badRequest().body(verifyFail("请填写 2-10 位中文真实姓名"));
        }

        // 学号唯一：不能拿别人的学号来认证
        User holder = userRepository.findByStuid(stuid);
        if (holder != null && !holder.getId().equals(user.getId())
                && Boolean.TRUE.equals(holder.getVerified())) {
            return ResponseEntity.badRequest().body(verifyFail("该学号已被其他账号认证"));
        }

        user.setRealName(realName);
        user.setVerified(true);
        user.setVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        return ResponseEntity.ok(verifyOk(user, "实名认证通过"));
    }

    private Map<String, Object> verifyOk(User user, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", message);
        result.put("verified", true);
        result.put("realNameMasked", maskName(user.getRealName()));
        return result;
    }

    private Map<String, Object> verifyFail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    /** 姓名脱敏：张三 -> 张*，欧阳娜娜 -> 欧***，只保留姓氏。 */
    private String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
