package com.example.demo.auth;

/**
 * 当前请求的调用方身份（ThreadLocal）。
 *
 * <p>由 {@link AuthInterceptor} 在请求进入时写入、请求结束时清除。
 * 这是**唯一**可信的身份来源：controller 里不要再从请求体/查询参数取 userId。</p>
 */
public final class AuthContext {

    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    static void set(AuthUser user) {
        CURRENT.set(user);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** 未登录返回 null。白名单接口用它做「登录了就走个人化、没登录就跳过」。 */
    public static Long userIdOrNull() {
        AuthUser user = CURRENT.get();
        return user == null ? null : user.id();
    }

    /**
     * 当前登录用户 id。受保护接口由拦截器保证非空；
     * 若这里抛异常，说明有接口忘了被拦截器保护，属于配置错误，应当立刻暴露。
     */
    public static Long requireUserId() {
        AuthUser user = CURRENT.get();
        if (user == null) {
            throw new IllegalStateException("该接口需要登录，但拦截器没有注入身份——检查 AuthInterceptor 的白名单");
        }
        return user.id();
    }

    public static boolean isAdmin() {
        AuthUser user = CURRENT.get();
        return user != null && user.isAdmin();
    }
}
