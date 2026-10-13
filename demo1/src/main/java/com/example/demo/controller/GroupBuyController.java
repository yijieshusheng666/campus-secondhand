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

import com.example.demo.entity.Goods;
import com.example.demo.entity.GroupBuy;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.GroupBuyRepository;
import com.example.demo.repository.UserRepository;

/**
 * 宿舍楼拼单（C3）。同一栋楼的同学凑人数，人满自动成团。
 * 加入时必须填写自己的宿舍楼，且必须与拼单楼号一致 —— 这是"同楼拼单"的硬约束。
 */
@RestController
@RequestMapping("/api/groupbuy")
public class GroupBuyController {

    private static final int MIN_TARGET = 2;
    private static final int MAX_TARGET = 20;

    @Autowired
    private GroupBuyRepository groupBuyRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 发起拼单（发起人自动成为第一个成员）
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long goodsId = longOf(body, "goodsId");
        Long initiatorId = longOf(body, "initiatorId");
        if (goodsId == null || initiatorId == null) {
            return ResponseEntity.badRequest().body(fail("请先登录"));
        }
        String building = text(body, "building");
        if (building.isEmpty()) {
            return ResponseEntity.badRequest().body(fail("请选择或填写你所在的宿舍楼"));
        }
        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(fail("商品不存在"));
        }
        if ("已售".equals(goods.getStatus())) {
            return ResponseEntity.badRequest().body(fail("这件宝贝已经售出，没法拼单了"));
        }

        Integer target = intOf(body, "targetCount");
        if (target == null || target < MIN_TARGET || target > MAX_TARGET) {
            return ResponseEntity.badRequest().body(fail(
                    "成团人数需要在 " + MIN_TARGET + "-" + MAX_TARGET + " 人之间"));
        }

        User initiator = userRepository.findById(initiatorId).orElse(null);
        GroupBuy groupBuy = new GroupBuy();
        groupBuy.setGoodsId(goodsId);
        groupBuy.setGoodsTitle(goods.getTitle());
        groupBuy.setInitiatorId(initiatorId);
        groupBuy.setInitiatorName(initiator != null ? initiator.getUsername() : "匿名同学");
        groupBuy.setBuilding(building);
        groupBuy.setTargetCount(target);
        groupBuy.getMemberIds().add(initiatorId);
        groupBuyRepository.save(groupBuy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("groupBuyId", groupBuy.getId());
        result.put("message", "拼单已发起（" + building + "），还差 "
                + (target - 1) + " 人成团");
        return ResponseEntity.ok(result);
    }

    // 某件商品进行中的拼单
    @GetMapping("/goods/{goodsId}")
    public ResponseEntity<?> byGoods(@PathVariable Long goodsId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GroupBuy groupBuy : groupBuyRepository.findByGoodsIdAndStatusOrderByCreatedAtDesc(
                goodsId, GroupBuy.STATUS_OPEN)) {
            items.add(view(groupBuy));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    // 我发起或参与的拼单
    @GetMapping
    public ResponseEntity<?> mine(@RequestParam Long userId) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (GroupBuy groupBuy : groupBuyRepository.findByStatusOrderByCreatedAtDesc(GroupBuy.STATUS_OPEN)) {
            if (groupBuy.getMemberIds().contains(userId)) {
                items.add(view(groupBuy));
            }
        }
        for (GroupBuy groupBuy : groupBuyRepository.findByStatusOrderByCreatedAtDesc(GroupBuy.STATUS_DONE)) {
            if (groupBuy.getMemberIds().contains(userId)) {
                items.add(view(groupBuy));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", items);
        return ResponseEntity.ok(result);
    }

    // 加入拼单：必须同楼、不能重复、不能加入已结束的
    @PostMapping("/{id}/join")
    @Transactional
    public ResponseEntity<?> join(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        GroupBuy groupBuy = groupBuyRepository.findById(id).orElse(null);
        if (groupBuy == null) {
            return ResponseEntity.badRequest().body(fail("拼单不存在"));
        }
        Long userId = longOf(body, "userId");
        if (userId == null) {
            return ResponseEntity.badRequest().body(fail("请先登录"));
        }
        if (!GroupBuy.STATUS_OPEN.equals(groupBuy.getStatus())) {
            return ResponseEntity.badRequest().body(fail("该拼单已" + groupBuy.getStatus() + "，不能加入了"));
        }
        if (groupBuy.getMemberIds().contains(userId)) {
            return ResponseEntity.badRequest().body(fail("你已经在这个拼单里了"));
        }
        String building = text(body, "building");
        if (building.isEmpty()) {
            return ResponseEntity.badRequest().body(fail("请填写你所在的宿舍楼"));
        }
        if (!building.equals(groupBuy.getBuilding())) {
            return ResponseEntity.badRequest().body(fail(
                    "这个拼单只限「" + groupBuy.getBuilding() + "」的同学，你填的是「" + building + "」"));
        }

        groupBuy.getMemberIds().add(userId);
        boolean full = groupBuy.getMemberIds().size() >= groupBuy.getTargetCount();
        if (full) {
            groupBuy.setStatus(GroupBuy.STATUS_DONE);
            groupBuy.setClosedAt(LocalDateTime.now());
        }
        groupBuyRepository.save(groupBuy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("status", groupBuy.getStatus());
        result.put("joinedCount", groupBuy.getMemberIds().size());
        result.put("message", full
                ? "拼单人数已满，成团成功！去消息里和同学们约个时间吧"
                : "已加入拼单，还差 " + (groupBuy.getTargetCount() - groupBuy.getMemberIds().size()) + " 人");
        return ResponseEntity.ok(result);
    }

    // 取消拼单（发起人或成员都可以退）
    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancel(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        GroupBuy groupBuy = groupBuyRepository.findById(id).orElse(null);
        if (groupBuy == null) {
            return ResponseEntity.badRequest().body(fail("拼单不存在"));
        }
        Long userId = longOf(body, "userId");
        if (userId == null || !groupBuy.getMemberIds().contains(userId)) {
            return ResponseEntity.badRequest().body(fail("你不在这个拼单里"));
        }
        if (GroupBuy.STATUS_DONE.equals(groupBuy.getStatus())) {
            return ResponseEntity.badRequest().body(fail("拼单已成团，不能取消了"));
        }

        if (userId.equals(groupBuy.getInitiatorId())) {
            // 发起人退出 = 整个拼单取消
            groupBuy.setStatus(GroupBuy.STATUS_CANCELLED);
            groupBuy.setClosedAt(LocalDateTime.now());
        } else {
            groupBuy.getMemberIds().remove(userId);
        }
        groupBuyRepository.save(groupBuy);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("status", groupBuy.getStatus());
        result.put("message", userId.equals(groupBuy.getInitiatorId())
                ? "已取消该拼单" : "已退出拼单");
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> view(GroupBuy groupBuy) {
        List<Map<String, Object>> members = new ArrayList<>();
        for (Long memberId : groupBuy.getMemberIds()) {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", memberId);
            member.put("name", userRepository.findById(memberId)
                    .map(User::getUsername).orElse("同学"));
            members.add(member);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", groupBuy.getId());
        item.put("goodsId", groupBuy.getGoodsId());
        item.put("goodsTitle", groupBuy.getGoodsTitle());
        item.put("initiatorId", groupBuy.getInitiatorId());
        item.put("initiatorName", groupBuy.getInitiatorName());
        item.put("building", groupBuy.getBuilding());
        item.put("targetCount", groupBuy.getTargetCount());
        item.put("joinedCount", groupBuy.getMemberIds().size());
        item.put("members", members);
        item.put("status", groupBuy.getStatus());
        item.put("createdAt", groupBuy.getCreatedAt());
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

    private Integer intOf(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
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
