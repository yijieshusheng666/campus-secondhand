package com.example.demo.controller;

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

        return ResponseEntity.ok(Map.of("success", true, "orderId", order.getId(), "message", "下单成功"));
    }

    // 查询订单：role=buyer 我买到的，role=seller 我卖出的
    @GetMapping
    public List<Order> list(@RequestParam Long userId, @RequestParam(defaultValue = "buyer") String role) {
        if ("seller".equals(role)) {
            return orderRepository.findBySellerIdOrderByCreatedAtDesc(userId);
        }
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(userId);
    }

    // 更新订单状态（发货 / 退款等），退款时商品重新上架
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Order order = orderRepository.findById(id).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "订单状态不能为空"));
        }
        order.setStatus(status);
        orderRepository.save(order);

        if ("已退款".equals(status)) {
            goodsRepository.findById(order.getGoodsId()).ifPresent(g -> {
                g.setStatus("在售");
                goodsRepository.save(g);
            });
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "订单状态已更新"));
    }
}
