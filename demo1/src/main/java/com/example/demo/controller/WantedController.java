package com.example.demo.controller;

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

import com.example.demo.auth.AuthContext;
import com.example.demo.entity.Goods;
import com.example.demo.entity.User;
import com.example.demo.entity.Wanted;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.WantedRepository;
import com.example.demo.service.WantedMatchService;
import com.example.demo.web.ApiResponses;
import com.example.demo.web.RequestUtils;

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

    // 发布求购：返回时顺带把首轮匹配结果带上，让"撮合"当场可见。发布人取自 token
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long userId = AuthContext.requireUserId();
        String title = RequestUtils.textOf(body, "title");
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("请填写想求购什么"));
        }
        if (title.length() > MAX_TITLE) {
            return ResponseEntity.badRequest().body(ApiResponses.fail(
                    "求购标题不能超过 " + MAX_TITLE + " 字"));
        }
        String description = RequestUtils.textOf(body, "description");
        if (description.length() > MAX_DESC) {
            return ResponseEntity.badRequest().body(ApiResponses.fail(
                    "补充说明不能超过 " + MAX_DESC + " 字"));
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("用户不存在"));
        }

        Wanted wanted = new Wanted();
        wanted.setUserId(userId);
        wanted.setUserName(user.getUsername());
        wanted.setTitle(title);
        wanted.setCategory(RequestUtils.textOf(body, "category"));
        wanted.setMaxPrice(RequestUtils.decimalOf(body, "maxPrice"));
        wanted.setDescription(description);
        wantedRepository.save(wanted);

        WantedMatchService.MatchResult matched = matchService.matchGoods(wanted, 6);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("wantedId", wanted.getId());
        extras.put("matches", matched.getGoods());
        extras.put("matchInfo", matched.getMatchInfo());
        extras.put("scanned", matched.getScanned());
        extras.put("semanticCount", matched.getSemanticCount());
        String msg = matched.getGoods().isEmpty()
                ? "求购已发布，比对完 " + matched.getScanned() + " 件商品，暂时没有匹配的"
                : "求购已发布，比对 " + matched.getScanned() + " 件商品，找到 "
                        + matched.getGoods().size() + " 件匹配的";
        return ResponseEntity.ok(ApiResponses.ok(msg, extras));
    }

    // 我的求购（口径固定为当前登录用户）
    @GetMapping
    public ResponseEntity<?> mine() {
        Long userId = AuthContext.requireUserId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : wantedRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            items.add(view(wanted));
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("data", items);
        return ResponseEntity.ok(ApiResponses.ok("ok", extras));
    }

    // 求购广场：所有进行中的需求
    @GetMapping("/open")
    public ResponseEntity<?> open() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : wantedRepository.findByStatusOrderByCreatedAtDesc(Wanted.STATUS_OPEN)) {
            items.add(view(wanted));
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("data", items);
        return ResponseEntity.ok(ApiResponses.ok("ok", extras));
    }

    // 某条求购匹配到的在售商品（附「凭什么匹配」的说明，便于核对）
    @GetMapping("/{id}/matches")
    public ResponseEntity<?> matches(@PathVariable Long id) {
        Wanted wanted = wantedRepository.findById(id).orElse(null);
        if (wanted == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("求购不存在"));
        }
        WantedMatchService.MatchResult matched = matchService.matchGoods(wanted, 6);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("data", matched.getGoods());
        extras.put("matchInfo", matched.getMatchInfo());
        extras.put("scanned", matched.getScanned());
        extras.put("semanticCount", matched.getSemanticCount());
        extras.put("wanted", view(wanted));
        return ResponseEntity.ok(ApiResponses.ok("ok", extras));
    }

    // 关闭求购（只有本人可以关）
    @PostMapping("/{id}/close")
    @Transactional
    public ResponseEntity<?> close(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Wanted wanted = wantedRepository.findById(id).orElse(null);
        if (wanted == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("求购不存在"));
        }
        // 关闭人取自 token：从前读 body 里的 userId，等于可以关掉别人发布的求购
        Long userId = AuthContext.requireUserId();
        if (!userId.equals(wanted.getUserId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("只能关闭自己发布的求购"));
        }
        if (Wanted.STATUS_CLOSED.equals(wanted.getStatus())) {
            return ResponseEntity.ok(ApiResponses.ok("该求购已经关闭"));
        }
        wanted.setStatus(Wanted.STATUS_CLOSED);
        wanted.setClosedAt(LocalDateTime.now());
        wantedRepository.save(wanted);
        return ResponseEntity.ok(ApiResponses.ok("求购已关闭"));
    }

    // 这件商品能命中哪些进行中的求购（发布商品成功后即时提示）
    @GetMapping("/match-for-goods")
    public ResponseEntity<?> matchForGoods(@RequestParam Long goodsId) {
        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("商品不存在"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Wanted wanted : matchService.matchWanted(goods,
                wantedRepository.findByStatusOrderByCreatedAtDesc(Wanted.STATUS_OPEN))) {
            items.add(view(wanted));
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("data", items);
        return ResponseEntity.ok(ApiResponses.ok("ok", extras));
    }

    private Map<String, Object> view(Wanted wanted) {
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
}
