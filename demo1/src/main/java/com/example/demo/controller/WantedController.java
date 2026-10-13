package com.example.demo.controller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Goods;
import com.example.demo.entity.User;
import com.example.demo.entity.Wanted;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WantedRepository;
import com.example.demo.service.WantedMatchService;

/**
 * 求购反向撮合（C1）：发布需求后由系统反向去找在售商品。
 */
@RestController
@RequestMapping("/api/wanted")
public class WantedController {

    private static final int MAX_TITLE = 40;
    private static final int MAX_DESC = 300;

    @Autowired
    private WantedRepository wantedRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private WantedMatchService matchService;

    // 发布求购：返回时顺带把首轮匹配结果带上，让"撮合"当场可见
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long userId = longOf(body, "userId");
        if (userId == null) {
            return ResponseEntity.badRequest().body(fail("请先登录"));
        }
        String title = text(body, "title");
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().body(fail("请填写想求购什么"));
        }
        if (title.length() > MAX_TITLE) {
            return ResponseEntity.badRequest().body(fail("求购标题不能超过 " + MAX_TITLE + " 字"));
        }
        String description = text(body, "description");
        if (description.length() > MAX_DESC) {
            return ResponseEntity.badRequest().body(fail("补充说明不能超过 " + MAX_DESC + " 字"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(fail("用户不存在"));
        }

        Wanted wanted = new Wanted();
        wanted.setUserId(userId);
        wanted.setUserName(user.getUsername());
        wanted.setTitle(title);
        wanted.setCategory(text(body, "category"));
        wanted.setMaxPrice(decimalOf(body, "maxPrice"));
        wanted.setDescription(description);
        wantedRepository.save(wanted);

        List<Goods> matched = matchService.matchGoods(wanted, 6);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("wantedId", wanted.getId());
        result.put("message", matched.isEmpty()
                ? "求购已发布，暂时没有匹配的在售商品"
                : "求购已发布，已为你找到 " + matched.size() + " 件匹配的宝贝");
        result.put("matches", matched);
        return ResponseEntity.ok(result);
    }

    // 我的求购
    @GetMapping
    public ResponseEntity<?> mine(@RequestParam Long userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : wantedRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            items.add(view(wanted, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    // 求购广场：所有进行中的需求
    @GetMapping("/open")
    public ResponseEntity<?> open() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : wantedRepository.findByStatusOrderByCreatedAtDesc(Wanted.STATUS_OPEN)) {
            items.add(view(wanted, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    // 某条求购匹配到的在售商品
    @GetMapping("/{id}/matches")
    public ResponseEntity<?> matches(@PathVariable Long id) {
        Wanted wanted = wantedRepository.findById(id).orElse(null);
        if (wanted == null) {
            return ResponseEntity.badRequest().body(fail("求购不存在"));
        }
        List<Goods> matched = matchService.matchGoods(wanted, 6);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", matched);
        result.put("wanted", view(wanted, false));
        return ResponseEntity.ok(result);
    }

    // 关闭求购（只有本人可以关）
    @PostMapping("/{id}/close")
    @Transactional
    public ResponseEntity<?> close(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Wanted wanted = wantedRepository.findById(id).orElse(null);
        if (wanted == null) {
            return ResponseEntity.badRequest().body(fail("求购不存在"));
        }
        Long userId = longOf(body, "userId");
        if (userId == null || !userId.equals(wanted.getUserId())) {
            return ResponseEntity.badRequest().body(fail("只能关闭自己发布的求购"));
        }
        if (Wanted.STATUS_CLOSED.equals(wanted.getStatus())) {
            return ResponseEntity.ok(Map.of("success", true, "message", "该求购已经关闭"));
        }
        wanted.setStatus(Wanted.STATUS_CLOSED);
        wanted.setClosedAt(LocalDateTime.now());
        wantedRepository.save(wanted);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "求购已关闭");
        return ResponseEntity.ok(result);
    }

    // 这件商品能命中哪些进行中的求购（发布商品成功后即时提示）
    @GetMapping("/match-for-goods")
    public ResponseEntity<?> matchForGoods(@RequestParam Long goodsId) {
        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(fail("商品不存在"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : matchService.matchWanted(goods,
                wantedRepository.findByStatusOrderByCreatedAtDesc(Wanted.STATUS_OPEN))) {
            items.add(view(wanted, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> view(Wanted wanted, boolean withMatches) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", wanted.getId());
        item.put("userId", wanted.getUserId());
        item.put("userName", wanted.getUserName());
        item.put("title", wanted.getTitle());
        item.put("category", wanted.getCategory());
        item.put("maxPrice", wanted.getMaxPrice());
        item.put("description", wanted.getDescription());
        item.put("status", wanted.getStatus());
        item.put("createdAt", wanted.getCreatedAt());
        return item;
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private Long longOf(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimalOf(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
