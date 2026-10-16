package com.example.demo.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

/**
 * 无状态登录令牌：{@code base64url(payload).base64url(HMAC-SHA256(payload))}。
 *
 * <p>为什么用签名 token 而不是 Session：这个项目是「Vue 5173 → Spring 8080 → FastAPI 8000」
 * 三进程结构，且后端要转发 AI 流式响应。Session 需要容器粘性会话与 Cookie 策略配合，
 * 而签名 token 是无状态的、放请求头即可，正好契合现有架构，也不引入 Redis。</p>
 *
 * <p>payload 只放 {@code uid / role / exp}，不放任何隐私字段——它只是 Base64，
 * 不是加密。签名保证的是「没被篡改」，不是「看不到」。</p>
 */
@Component
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long ttlSeconds;

    public TokenService(@Value("${app.auth.secret:}") String configuredSecret,
                        @Value("${app.auth.ttl-seconds:604800}") long ttlSeconds) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("app.auth.secret 未配置，已生成临时密钥：后端一重启所有登录态都会失效。"
                    + "请在 application.properties 里设置 app.auth.secret 为一个固定值。");
        } else {
            this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.ttlSeconds = ttlSeconds;
    }

    /** 签发令牌。 */
    public String issue(Long userId, String role) {
        JSONObject payload = new JSONObject();
        payload.put("uid", userId);
        payload.put("role", role == null || role.isBlank() ? "user" : role);
        payload.put("exp", System.currentTimeMillis() / 1000 + ttlSeconds);
        String body = encode(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
        return body + "." + encode(sign(body));
    }

    /** 校验签名与有效期；任何一步不通过都返回 null，由调用方判定为未登录。 */
    public AuthUser verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        String body = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        if (!MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8),
                                   encode(sign(body)).getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        try {
            JSONObject payload = JSON.parseObject(
                    new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8));
            Long uid = payload.getLong("uid");
            Long exp = payload.getLong("exp");
            if (uid == null || exp == null || exp < System.currentTimeMillis() / 1000) {
                return null;
            }
            return new AuthUser(uid, payload.getString("role"));
        } catch (Exception e) {
            // 伪造/损坏的 token 走这里，静默当作未登录，不把异常细节回给客户端
            return null;
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    private static String encode(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
