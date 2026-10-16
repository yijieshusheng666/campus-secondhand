package com.example.demo.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 登录令牌的签发与校验（纯单元测试，不需要 Spring 上下文）。
 *
 * <p>token 是整个鉴权体系的信任根：只要 verify 能被绕过，
 * 「服务端校验角色」这些全都会失效，所以这条线必须有测试守着。</p>
 */
class TokenServiceTest {

    private static final String SECRET = "unit-test-secret";

    @Test
    void issuedTokenRoundTrips() {
        TokenService service = new TokenService(SECRET, 3600);
        String token = service.issue(42L, "admin");

        AuthUser user = service.verify(token);
        assertNotNull(user);
        assertEquals(42L, user.id());
        assertEquals("admin", user.role());
        assertTrue(user.isAdmin());
    }

    @Test
    void roleDefaultsToUserWhenBlank() {
        TokenService service = new TokenService(SECRET, 3600);
        AuthUser user = service.verify(service.issue(7L, null));
        assertNotNull(user);
        assertEquals("user", user.role());
        assertTrue(!user.isAdmin());
    }

    @Test
    void tamperedPayloadIsRejected() {
        TokenService service = new TokenService(SECRET, 3600);
        String token = service.issue(1L, "user");

        // 把载荷换成 admin 的（签名不变）——克隆 token 提权的经典手法
        String forged = "eyJ1aWQiOjEsInJvbGUiOiJhZG1pbiIsImV4cCI6NDEwMjQ0NDgwMH0"
                + token.substring(token.indexOf('.'));
        assertNull(service.verify(forged), "载荷被改过必须验签失败");
    }

    @Test
    void tokenFromAnotherSecretIsRejected() {
        String foreign = new TokenService("another-secret", 3600).issue(1L, "admin");
        assertNull(new TokenService(SECRET, 3600).verify(foreign), "别的密钥签的 token 不能认");
    }

    @Test
    void expiredTokenIsRejected() {
        // ttl 传负数 => exp 立刻过期
        TokenService service = new TokenService(SECRET, -10);
        assertNull(service.verify(service.issue(1L, "user")));
    }

    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        TokenService service = new TokenService(SECRET, 3600);
        assertNull(service.verify(null));
        assertNull(service.verify(""));
        assertNull(service.verify("没有点号"));
        assertNull(service.verify("."));
        assertNull(service.verify("a."));
        assertNull(service.verify(".b"));
        assertNull(service.verify("!!!.???"), "垃圾输入应当被当作未登录，而不是抛异常");
    }
}
