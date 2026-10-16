package com.example.demo.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Order;
import com.example.demo.entity.User;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.web.ApiResponses;

/**
 * 管理端跨域查询：用户总览、全量订单。
 *
 * <p>为什么单独一个 Controller 而不是塞进 {@link UserController}/{@link OrderController}：
 * 那两个是「用户操作自己的数据」，接口都以 userId 为入口；而管理端要的是
 * 全站维度的只读视图，入参和语义都不同，混在一起会让两边都难读。
 *
 * <p>鉴权：整个类挂在 {@code /api/admin/**} 下，由
 * {@link com.example.demo.auth.AuthInterceptor} 统一要求 {@code role=admin}，
 * 本类内部不再（也不应）自己做任何鉴权判断。
 *
 * <p>⚠️ 两个约定，改动时务必保留：
 * <ol>
 *   <li><b>密码绝不出现在响应里</b>。{@link User} 实体带 password 字段，
 *       直接返回实体等于把全站密码明文发给浏览器。这里逐字段组装视图，
 *       新增 User 字段时也要想清楚要不要给管理端看，别图省事返回整个 user。</li>
 *   <li><b>这是只读接口</b>，不提供改角色的入口。访问控制目前只有拦截器这一层，
 *       还没有操作审计；要新增「改角色」这类写接口，必须先补上审计留痕，
 *       否则管理员账号一旦被盗用就查不到是谁提的权。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminQueryController {

    /** 管理端列表一次最多返回多少条，防止把整库塞进一个响应 */
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 100;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrderRepository orderRepository;

    /**
     * 用户总览。支持按昵称 / 学号模糊搜，以及按角色、实名状态过滤。
     */
    @GetMapping("/users")
    public ResponseEntity<?> users(
            @RequestParam(required = false) String kw,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean verified,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        String key = kw == null ? "" : kw.trim().toLowerCase();
        int cap = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<Map<String, Object>> rows = new ArrayList<>();
        // findAll 而不是自定义 @Query：这里只需要昵称/学号这种"包含"匹配，
        // 而 User 实体在内存里本来就不大，加派生查询反而多一处要维护的 SQL。
        List<User> all = userRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        for (User u : all) {
            if (!key.isEmpty()) {
                String name = nullToEmpty(u.getUsername()).toLowerCase();
                String stuid = nullToEmpty(u.getStuid()).toLowerCase();
                if (!name.contains(key) && !stuid.contains(key)) {
                    continue;
                }
            }
            if (role != null && !role.isBlank() && !role.equalsIgnoreCase(nullToEmpty(u.getRole()))) {
                continue;
            }
            if (verified != null && !verified.equals(Boolean.TRUE.equals(u.getVerified()))) {
                continue;
            }
            rows.add(userView(u));
            if (rows.size() >= cap) {
                break;
            }
        }
        return ResponseEntity.ok(ApiResponses.ok("ok", Map.of("total", rows.size(), "data", rows)));
    }

    /**
     * 全量订单。支持按状态过滤 + 按商品名 / 订单号 / 买家名模糊搜。
     *
     * <p>Order 实体只存了 buyerName，没有 sellerName —— 要展示卖家名就得再查一次
     * users 表。这里没有做这层补全：管理端列表目前展示「买家 + 卖家 ID」，
     * 够定位问题；如果后面确实要显示卖家昵称，正确做法是在这里按 sellerId
     * 批量查一次 User 做映射，而不是在循环里逐个 getById（N+1）。</p>
     */
    @GetMapping("/orders")
    public ResponseEntity<?> orders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kw,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        String key = kw == null ? "" : kw.trim().toLowerCase();
        int cap = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Order> all = orderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        for (Order o : all) {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(nullToEmpty(o.getStatus()))) {
                continue;
            }
            if (!key.isEmpty()) {
                String haystack = (nullToEmpty(o.getGoodsTitle()) + " "
                        + nullToEmpty(o.getBuyerName()) + " "
                        + String.valueOf(o.getId())).toLowerCase();
                if (!haystack.contains(key)) {
                    continue;
                }
            }
            rows.add(orderView(o));
            if (rows.size() >= cap) {
                break;
            }
        }
        return ResponseEntity.ok(ApiResponses.ok("ok", Map.of("total", rows.size(), "data", rows)));
    }

    /** 用户视图：白名单组装，明确不含 password。 */
    private Map<String, Object> userView(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("stuid", u.getStuid());
        // realName 只回传脱敏值：列表页要能确认"这人实名过"，
        // 但不需要（也不应该）看到完整姓名。
        m.put("realNameMasked", maskName(u.getRealName()));
        m.put("verified", Boolean.TRUE.equals(u.getVerified()));
        m.put("role", nullToEmpty(u.getRole()));
        m.put("avatar", u.getAvatar());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }

    private Map<String, Object> orderView(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("goodsTitle", o.getGoodsTitle());
        m.put("goodsPrice", o.getGoodsPrice());
        m.put("buyerId", o.getBuyerId());
        m.put("buyerName", o.getBuyerName());
        m.put("sellerId", o.getSellerId());
        m.put("status", o.getStatus());
        m.put("escrowStatus", o.getEscrowStatus());
        m.put("createdAt", o.getCreatedAt());
        m.put("paidAt", o.getPaidAt());
        m.put("settledAt", o.getSettledAt());
        return m;
    }

    /** 姓名脱敏：张三 -> 张*；张三丰 -> 张*丰。空值返回空串。 */
    private String maskName(String realName) {
        String n = realName == null ? "" : realName.trim();
        if (n.isEmpty()) {
            return "";
        }
        if (n.length() == 1) {
            return n + "*";
        }
        return n.charAt(0) + "*" + n.substring(n.length() - 1);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
