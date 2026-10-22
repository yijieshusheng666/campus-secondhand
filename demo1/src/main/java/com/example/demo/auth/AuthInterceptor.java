package com.example.demo.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 统一鉴权入口。三条规则：
 * <ol>
 *   <li>白名单（登录注册、浏览商品、看评价、智能客服等）不需要 token；</li>
 *   <li>其余 {@code /api/**} 一律要求有效 token；</li>
 *   <li>管理端/运营端（{@code /api/admin/**}、{@code /api/ai/ops/**}、{@code /api/ai/reindex}）
 *       额外要求 {@code role=admin}。</li>
 * </ol>
 *
 * <p>为什么必须集中在拦截器里：以前每个接口各自从请求体取 {@code userId}，
 * 等于「谁调用谁说了算」——任意人传一个 id 就能以他人名义下单、删商品、裁定纠纷，
 * 而 {@code DisputeController} 里那句「校验 handler 是不是 admin」也只是在核对
 * 攻击者自己传进来的 id。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 方法 + 路径白名单。路径用 Ant 风格，{@code *} 匹配单段。 */
    private static final List<String[]> PUBLIC = List.of(
            new String[]{"POST", "/api/login"},
            new String[]{"POST", "/api/register"},
            new String[]{"GET", "/api"},
            new String[]{"GET", "/api/goods"},
            new String[]{"GET", "/api/goods/*"},
            new String[]{"GET", "/api/users/*"},
            new String[]{"GET", "/api/reviews/goods/*"},
            new String[]{"GET", "/api/reviews/credit"},
            new String[]{"GET", "/api/wanted/open"},
            new String[]{"POST", "/api/recommend"},
            new String[]{"GET", "/api/ai/search"},
            new String[]{"GET", "/api/ai/price-suggest"},
            // 智能客服悬浮按钮对所有访客可见（App.vue 的 FAB 没有登录门槛），
            // 所以这两条不能要求登录，否则匿名访客一点就是 401。
            new String[]{"POST", "/api/ai/chat"},
            new String[]{"POST", "/api/ai/chat/stream"}
    );

    /**
     * 仅管理员可访问。
     *
     * <p>纠纷列表与裁定两个接口挂在 {@code /api/disputes} 下而不是 {@code /api/admin}，
     * 但它们是管理端专用（待处理纠纷列表、裁定退款/放款），所以必须在这里显式列出——
     * 只按路径前缀判断会漏掉它们。</p>
     */
    private static final List<String> ADMIN_ONLY = List.of(
            "/api/admin/**",
            "/api/ai/ops/**",
            "/api/ai/reindex",
            "/api/disputes/pending",
            "/api/disputes/*/rule"
    );

    private final TokenService tokens;

    public AuthInterceptor(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // 只对「真正来自客户端的那一次派发」做鉴权。
        //
        // 容器内部的转发必须放行：任何 handler 里抛异常、或调 response.sendError(...)，
        // 都会触发一次 ERROR 派发到 /error；如果那次也拦，真实的 400/404/500 会被替换成一个
        // 与原因毫无关系的「请先登录」，排查时完全看不出发生了什么（实测：匿名请求
        // /api/reviews/credit 少了必填参数本应 400，却被这里回成 401）。
        // 注意这不构成绕过：ERROR 派发只可能由已经过一次 REQUEST 鉴权的请求触发。
        if (request.getDispatcherType() != jakarta.servlet.DispatcherType.REQUEST) {
            return true;
        }

        // CORS 预检不带自定义请求头，若在这里拦掉浏览器会报跨域失败
        if ("OPTIONS".equals(method)) {
            return true;
        }

        AuthUser user = tokens.verify(extractToken(request));
        if (user != null) {
            AuthContext.set(user);
        }

        if (isPublic(method, path)) {
            return true;
        }
        if (user == null) {
            return reject(response, HttpStatus.UNAUTHORIZED, "请先登录");
        }
        if (isAdminOnly(path) && !user.isAdmin()) {
            return reject(response, HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程会被容器复用，不清会串到下一个请求上
        AuthContext.clear();
    }

    /** 取 {@code Authorization: Bearer xxx}，兼容自定义头 {@code X-Auth-Token}。 */
    private static String extractToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        String custom = request.getHeader("X-Auth-Token");
        return custom == null ? null : custom.trim();
    }

    private static boolean isPublic(String method, String path) {
        for (String[] rule : PUBLIC) {
            if (rule[0].equals(method) && MATCHER.match(rule[1], path)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdminOnly(String path) {
        for (String pattern : ADMIN_ONLY) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 401/403 也用统一响应形状，前端不必为鉴权失败单独写一套解析。 */
    private static boolean reject(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\"}");
        return false;
    }
}
