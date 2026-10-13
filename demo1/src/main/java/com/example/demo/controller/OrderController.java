package com.example.demo.controller;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Goods;
import com.example.demo.entity.Order;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.OrderStateMachine;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 创建订单：保存订单快照并把商品标记为已售
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long goodsId = Long.valueOf(body.get("goodsId").toString());
        Long buyerId = Long.valueOf(body.get("buyerId").toString());
        String receiver = (String) body.get("receiver");
        String phone = (String) body.get("phone");
        String address = (String) body.get("address");

        if (receiver == null || phone == null || address == null
                || receiver.isBlank() || phone.isBlank() || address.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "请填写收货人、联系电话和地址"));
        }

        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "商品不存在"));
        }
        if ("已售".equals(goods.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "商品已售出"));
        }

        Order order = new Order();
        order.setGoodsId(goods.getId());
        order.setGoodsTitle(goods.getTitle());
        order.setGoodsPrice(goods.getPrice());
        order.setGoodsImage(goods.getImages().isEmpty() ? "" : goods.getImages().get(0).getImageUrl());
        order.setBuyerId(buyerId);
        User buyer = userRepository.findById(buyerId).orElse(null);
        order.setBuyerName(buyer != null ? buyer.getUsername() : "未知用户");
        order.setSellerId(goods.getUserId());
        order.setReceiver(receiver);
        order.setPhone(phone);
        order.setAddress(address);
        orderRepository.save(order);

        goods.setStatus("已售");
        goodsRepository.save(goods);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderId", order.getId());
        result.put("status", order.getStatus());
        result.put("escrowStatus", order.getEscrowStatus());
        result.put("message", "下单成功，请完成付款，货款将由平台托管");
        return ResponseEntity.ok(result);
    }

    // 查询订单：role=buyer 我买到的，role=seller 我卖出的
    @GetMapping
    public List<Order> list(@RequestParam Long userId, @RequestParam(defaultValue = "buyer") String role) {
        if ("seller".equals(role)) {
            return orderRepository.findBySellerIdOrderByCreatedAtDesc(userId);
        }
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 更新订单状态。所有流转都必须通过担保交易状态机校验：
     * 1) 状态必须存在；2) 流转必须合法；3) 操作人必须是该环节的合法一方。
     * 退款或取消后商品自动重新上架。
     */
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }

        String target = text(body, "status");
        if (target.isBlank()) {
            return ResponseEntity.badRequest().body(fail("订单状态不能为空"));
        }
        if (!OrderStateMachine.isKnown(target)) {
            return ResponseEntity.badRequest().body(fail("未知的订单状态：" + target));
        }

        String current = order.getStatus() == null ? OrderStateMachine.PENDING_PAY : order.getStatus();
        if (!OrderStateMachine.canTransfer(current, target)) {
            return ResponseEntity.badRequest().body(fail(
                    "不能从「" + current + "」直接变成「" + target + "」。"
                            + OrderStateMachine.describeNext(current)));
        }

        Long actorId = longOf(body, "userId");
        if (actorId == null) {
            return ResponseEntity.badRequest().body(fail("缺少操作人 userId"));
        }
        String required = OrderStateMachine.actorOf(current, target);
        if (OrderStateMachine.ACTOR_BUYER.equals(required) && !actorId.equals(order.getBuyerId())) {
            return ResponseEntity.badRequest().body(fail("该操作只能由买家发起"));
        }
        if (OrderStateMachine.ACTOR_SELLER.equals(required) && !actorId.equals(order.getSellerId())) {
            return ResponseEntity.badRequest().body(fail("该操作只能由卖家发起"));
        }

        order.setStatus(target);
        order.setEscrowStatus(OrderStateMachine.escrowOf(target));
        LocalDateTime now = LocalDateTime.now();
        if (OrderStateMachine.PAID.equals(target)) {
            order.setPaidAt(now);        // 付款：货款进入平台托管
        }
        if (OrderStateMachine.FINISHED.equals(target)) {
            order.setSettledAt(now);     // 确认收货：平台放款给卖家，担保结束
        }
        orderRepository.save(order);

        // 订单退款或取消后，商品重新上架
        if (OrderStateMachine.REFUNDED.equals(target) || OrderStateMachine.CANCELLED.equals(target)) {
            goodsRepository.findById(order.getGoodsId()).ifPresent(g -> {
                if (!"在售".equals(g.getStatus())) {
                    g.setStatus("在售");
                    goodsRepository.save(g);
                }
            });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "订单状态已更新为「" + target + "」");
        result.put("status", target);
        result.put("escrowStatus", order.getEscrowStatus());
        return ResponseEntity.ok(result);
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

    private Map<String, Object> fail(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
