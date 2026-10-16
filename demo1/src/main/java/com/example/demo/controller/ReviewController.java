package com.example.demo.controller;

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
import com.example.demo.entity.Order;
import com.example.demo.entity.Review;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.ReviewRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CreditService;
import com.example.demo.web.ApiResponses;

/**
 * 双向评价与信用分（B3）。
 * 被评价人由后端根据订单推导，不接受前端传入，避免伪造评价对象。
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private static final int MAX_CONTENT = 200;
    private static final String REVIEWABLE_STATUS = "已完成";

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private CreditService creditService;

    // 提交评价（订单完成后，买卖双方各可评一次）
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        if (body.get("orderId") == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("缺少订单或用户标识"));
        }
        Long orderId = Long.valueOf(body.get("orderId").toString());
        // 评价人取自 token：从前读 body 里的 reviewerId，等于可以冒充买卖任意一方去给自己刷分
        Long reviewerId = AuthContext.requireUserId();

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("订单不存在"));
        }
        // 历史数据里存在买卖双方为同一人的订单，这种订单不存在"对方"，直接不允许评价
        if (order.getBuyerId() == null || order.getBuyerId().equals(order.getSellerId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("这笔订单的买卖双方是同一人，无法评价"));
        }

        String role;
        Long revieweeId;
        if (reviewerId.equals(order.getBuyerId())) {
            role = "buyer";
            revieweeId = order.getSellerId();
        } else if (reviewerId.equals(order.getSellerId())) {
            role = "seller";
            revieweeId = order.getBuyerId();
        } else {
            return ResponseEntity.badRequest().body(ApiResponses.fail("你不是这笔订单的参与方"));
        }
        // 兜底：不能给自己打分，否则信用分会被自己刷高
        if (revieweeId == null || revieweeId.equals(reviewerId)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("不能评价自己"));
        }

        if (!REVIEWABLE_STATUS.equals(order.getStatus())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("订单完成后才能评价"));
        }
        if (reviewRepository.existsByOrderIdAndReviewerId(orderId, reviewerId)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("这笔订单你已经评价过了"));
        }

        Integer score = parseScore(body.get("score"));
        if (score == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("请给 1-5 星评价"));
        }

        String content = body.get("content") == null ? "" : body.get("content").toString().trim();
        if (content.length() > MAX_CONTENT) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("评价内容不能超过 " + MAX_CONTENT + " 字"));
        }

        Review review = new Review();
        review.setOrderId(orderId);
        review.setGoodsId(order.getGoodsId());
        review.setReviewerId(reviewerId);
        review.setRevieweeId(revieweeId);
        review.setRole(role);
        review.setScore(score);
        review.setContent(content);
        reviewRepository.save(review);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "评价已提交");
        result.put("revieweeScore", creditService.scoreOf(revieweeId));
        return ResponseEntity.ok(result);
    }

    // 我收到的评价 + 我发出的评价 + 信用概览（口径固定为当前登录用户）
    @GetMapping
    public ResponseEntity<?> list() {
        Long userId = AuthContext.requireUserId();
        List<Map<String, Object>> received = new ArrayList<>();
        for (Review review : reviewRepository.findByRevieweeIdOrderByCreatedAtDesc(userId)) {
            received.add(view(review));
        }
        List<Map<String, Object>> sent = new ArrayList<>();
        for (Review review : reviewRepository.findByReviewerIdOrderByCreatedAtDesc(userId)) {
            sent.add(view(review));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", received);
        result.put("sent", sent);
        result.put("credit", creditService.summaryOf(userId));
        return ResponseEntity.ok(result);
    }

    // 这笔订单我评价过没有（前端据此决定按钮文案）。"我"取自 token，不再接受外部 reviewerId
    @GetMapping("/order")
    public ResponseEntity<?> byOrder(@RequestParam Long orderId) {
        Long reviewerId = AuthContext.requireUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("reviewed", reviewRepository.existsByOrderIdAndReviewerId(orderId, reviewerId));
        return ResponseEntity.ok(result);
    }

    // 某件商品收到的评价 + 评分汇总（商品详情页展示用）
    @GetMapping("/goods/{goodsId}")
    public ResponseEntity<?> byGoods(@PathVariable Long goodsId) {
        List<Review> reviews = reviewRepository.findByGoodsIdOrderByCreatedAtDesc(goodsId);

        double sum = 0;
        int buyers = 0;
        int sellers = 0;
        for (Review review : reviews) {
            sum += review.getScore() == null ? 0 : review.getScore();
            if ("buyer".equals(review.getRole())) {
                buyers++;
            } else {
                sellers++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", reviews.size());
        summary.put("avgScore", reviews.isEmpty() ? 0 : Math.round(sum / reviews.size() * 10) / 10.0);
        summary.put("fromBuyers", buyers);      // 买家评卖家
        summary.put("fromSellers", sellers);    // 卖家评买家

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("summary", summary);
        result.put("data", viewList(reviews));
        return ResponseEntity.ok(result);
    }

    // 信用分
    @GetMapping("/credit")
    public ResponseEntity<?> credit(@RequestParam Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.putAll(creditService.summaryOf(userId));
        return ResponseEntity.ok(result);
    }

    /** 组装一条评价：同时带上评价人和被评价人昵称，收到/发出两种列表都能复用。 */
    private Map<String, Object> view(Review review) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", review.getId());
        item.put("orderId", review.getOrderId());
        item.put("score", review.getScore());
        item.put("content", review.getContent());
        item.put("role", review.getRole());
        item.put("createdAt", review.getCreatedAt());
        item.put("reviewerId", review.getReviewerId());
        item.put("revieweeId", review.getRevieweeId());
        item.put("reviewerName", nameOf(review.getReviewerId()));
        item.put("revieweeName", nameOf(review.getRevieweeId()));

        Goods goods = goodsRepository.findById(review.getGoodsId()).orElse(null);
        item.put("goodsTitle", goods != null ? goods.getTitle() : "");
        return item;
    }

    private List<Map<String, Object>> viewList(List<Review> reviews) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Review review : reviews) {
            items.add(view(review));
        }
        return items;
    }

    private String nameOf(Long userId) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        return user != null ? user.getUsername() : "匿名同学";
    }

    private Integer parseScore(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            int score = (int) Math.round(Double.parseDouble(raw.toString()));
            return (score >= 1 && score <= 5) ? score : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
