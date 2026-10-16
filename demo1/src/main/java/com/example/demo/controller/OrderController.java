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

import com.example.demo.auth.AuthContext;
import com.example.demo.entity.Goods;
import com.example.demo.entity.Order;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.OrderStateMachine;
import com.example.demo.web.ApiResponses;
import com.example.demo.web.RequestUtils;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 创建订单：保存订单快照并把商品标记为已售。买家取自 token，不再读 body 里的 buyerId
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long goodsId = RequestUtils.longOf(body, "goodsId");
        Long buyerId = AuthContext.requireUserId();
        if (goodsId == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("缺少商品或买家标识"));
        }
        String receiver = RequestUtils.textOf(body, "receiver");
        String phone = RequestUtils.textOf(body, "phone");
        String address = RequestUtils.textOf(body, "address");
        if (receiver.isEmpty() || phone.isEmpty() || address.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("请填写收货人、联系电话和地址"));
        }

        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("商品不存在"));
        }
        // 买家不能给自己的商品下单。买家身份现在来自 token，这条校验才真正拦得住人
        if (buyerId.equals(goods.getUserId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("不能给自己的商品下单"));
        }
        if ("已售".equals(goods.getStatus())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("商品已售出"));
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

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("orderId", order.getId());
        extras.put("status", order.getStatus());
        extras.put("escrowStatus", order.getEscrowStatus());
        return ResponseEntity.ok(ApiResponses.ok(
                "下单成功，请完成付款，货款将由平台托管", extras));
    }

    // 查询订单：role=buyer 我买到的，role=seller 我卖出的（"我"固定为当前登录用户）
    @GetMapping
    public List<Order> list(@RequestParam(defaultValue = "buyer") String role) {
        Long userId = AuthContext.requireUserId();
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

        String target = RequestUtils.textOf(body, "status");
        if (target.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("订单状态不能为空"));
        }
        if (!OrderStateMachine.isKnown(target)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("未知的订单状态：" + target));
        }

        String current = order.getStatus() == null ? OrderStateMachine.PENDING_PAY : order.getStatus();
        if (!OrderStateMachine.canTransfer(current, target)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail(
                    "不能从「" + current + "」直接变成「" + target + "」。"
                            + OrderStateMachine.describeNext(current)));
        }

        // 操作人取自 token：以前读 body 里的 userId，任意人传买卖双方的 id 就能推进别人的订单
        Long actorId = AuthContext.requireUserId();
        // 状态机声明该流转由谁发起，就必须由谁发起 —— 认不出来一律拒绝，不做「不认识就放行」。
        // 这里曾经只处理 buyer/seller 两种情况，而「仲裁中 → 已退款 / 已完成」的操作方是
        // admin，两个 if 都不进，结果任何登录用户都能直接裁定一笔纠纷、决定货款退给谁。
        String required = OrderStateMachine.actorOf(current, target);
        if (required.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail(
                    "该流转未定义操作方，已拒绝：" + current + "→" + target));
        }
        if (OrderStateMachine.ACTOR_BUYER.equals(required) && !actorId.equals(order.getBuyerId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("该操作只能由买家发起"));
        }
        if (OrderStateMachine.ACTOR_SELLER.equals(required) && !actorId.equals(order.getSellerId())) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("该操作只能由卖家发起"));
        }
        if (!OrderStateMachine.ACTOR_BUYER.equals(required)
                && !OrderStateMachine.ACTOR_SELLER.equals(required)
                && !OrderStateMachine.ACTOR_ADMIN.equals(required)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail(
                    "未知的操作方定义，已拒绝：" + required));
        }
        if (OrderStateMachine.ACTOR_ADMIN.equals(required) && !AuthContext.isAdmin()) {
            return ResponseEntity.status(403).body(ApiResponses.fail("该操作只能由平台管理员发起"));
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

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("status", target);
        extras.put("escrowStatus", order.getEscrowStatus());
        return ResponseEntity.ok(ApiResponses.ok(
                "订单状态已更新为「" + target + "」", extras));
    }
}
