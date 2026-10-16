package com.example.demo.auth;

/**
 * 已认证的调用方身份，由 token 解析而来。
 *
 * <p>关键区别：这个对象是**服务端签名背书的**，请求体/查询参数里的 {@code userId}
 * 一律不可信。以前全项目都拿客户端传的 userId 当身份，改一个 DevTools 里的数字
 * 就能以他人名义下单、删商品、裁决纠纷。</p>
 */
public record AuthUser(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
