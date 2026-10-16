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

import com.example.demo.entity.Barter;
import com.example.demo.entity.Goods;
import com.example.demo.entity.User;
import com.example.demo.repository.BarterRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.web.ApiResponses;
import com.example.demo.web.RequestUtils;

/**
 * 以物换物（C4）。平台不涉及资金：双方同意后自行沟通交接。
 * 只有商品拥有者能回应，且同一件商品同一人同时只能有一条待回应请求。
 */
@RestController
@RequestMapping("/api/barter")
public class BarterController {

    private static final int MAX_OFFER = 40;
    private static final int MAX_DESC = 300;

    @Autowired
    private BarterRepository barterRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 发起换物请求
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long goodsId = RequestUtils.longOf(body, "goodsId");
        Long requesterId = RequestUtils.longOf(body, "requesterId");
        if (goodsId == null || requesterId == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("请先登录"));
        }
        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("商品不存在"));
        }
        if (requesterId.equals(goods.getUserId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("不能拿自己的东西跟自己换"));
        }
        String offerTitle = RequestUtils.textOf(body, "offerTitle");
        if (offerTitle.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("请填写你打算用什么来换"));
        }
        if (offerTitle.length() > MAX_OFFER) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("换物物品不能超过 " + MAX_OFFER + " 字"));
        }
        String offerDesc = RequestUtils.textOf(body, "offerDesc");
        if (offerDesc.length() > MAX_DESC) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("补充说明不能超过 " + MAX_DESC + " 字"));
        }
        if (barterRepository.existsByGoodsIdAndRequesterIdAndStatus(
                goodsId, requesterId, Barter.STATUS_PENDING)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("你已经对这件宝贝发起过换物请求了，等对方回应吧"));
        }

        User requester = userRepository.findById(requesterId).orElse(null);
        Barter barter = new Barter();
        barter.setGoodsId(goodsId);
        barter.setGoodsTitle(goods.getTitle());
        barter.setOwnerId(goods.getUserId());
        barter.setRequesterId(requesterId);
        barter.setRequesterName(requester != null ? requester.getUsername() : "匿名同学");
        barter.setOfferTitle(offerTitle);
        barter.setOfferDesc(offerDesc);
        barterRepository.save(barter);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("barterId", barter.getId());
        return ResponseEntity.ok(ApiResponses.ok("换物请求已发出，等对方回应", extras));
    }

    // 我发起的 + 我收到的
    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long userId) {
        List<Map<String, Object>> sent = new ArrayList<>();
        for (Barter barter : barterRepository.findByRequesterIdOrderByCreatedAtDesc(userId)) {
            sent.add(view(barter));
        }
        List<Map<String, Object>> received = new ArrayList<>();
        for (Barter barter : barterRepository.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            received.add(view(barter));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sent", sent);
        data.put("received", received);
        return ResponseEntity.ok(ApiResponses.ok("ok", data));
    }

    // 商品拥有者回应换物请求
    @PostMapping("/{id}/respond")
    @Transactional
    public ResponseEntity<?> respond(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Barter barter = barterRepository.findById(id).orElse(null);
        if (barter == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("换物请求不存在"));
        }
        Long userId = RequestUtils.longOf(body, "userId");
        if (userId == null || !userId.equals(barter.getOwnerId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("只有宝贝的主人可以回应换物请求"));
        }
        if (!Barter.STATUS_PENDING.equals(barter.getStatus())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("这条换物请求已经回应过了"));
        }

        boolean accept = Boolean.TRUE.equals(body.get("accept"))
                || "true".equalsIgnoreCase(RequestUtils.textOf(body, "accept"));
        barter.setStatus(accept ? Barter.STATUS_AGREED : Barter.STATUS_REJECTED);
        barter.setRespondedAt(LocalDateTime.now());
        barterRepository.save(barter);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("status", barter.getStatus());
        String msg = accept ? "已同意换物，去消息里和对方约个时间吧" : "已拒绝该换物请求";
        return ResponseEntity.ok(ApiResponses.ok(msg, extras));
    }

    private Map<String, Object> view(Barter barter) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", barter.getId());
        item.put("goodsId", barter.getGoodsId());
        item.put("goodsTitle", barter.getGoodsTitle());
        item.put("requesterId", barter.getRequesterId());
        item.put("requesterName", barter.getRequesterName());
        item.put("ownerId", barter.getOwnerId());
        item.put("offerTitle", barter.getOfferTitle());
        item.put("offerDesc", barter.getOfferDesc());
        item.put("status", barter.getStatus());
        item.put("createdAt", barter.getCreatedAt());
        item.put("respondedAt", barter.getRespondedAt());
        return item;
    }
}
