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

import com.example.demo.entity.Dispute;
import com.example.demo.entity.Goods;
import com.example.demo.entity.Order;
import com.example.demo.entity.User;
import com.example.demo.repository.DisputeRepository;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.OrderRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.OrderStateMachine;

/**
 * 纠纷仲裁（B4）。
 *
 * 入口只有一条：**买家对「已发货」的订单申请平台介入**。判定由 role=admin 的账号作出，
 * 且只有两种结局 —— 支持买家退款，或驳回诉求、视为交易完成并把货款放给卖家。
 * 判定结果直接驱动订单状态机流转，不是另起一套状态。
 */
@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private static final String ROLE_ADMIN = "admin";
    private static final int MAX_DETAIL = 500;

    @Autowired
    private DisputeRepository disputeRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 买家申请平台介入
    @PostMapping
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        Long orderId = longOf(body, "orderId");
        Long initiatorId = longOf(body, "initiatorId");
        if (orderId == null || initiatorId == null) {
            return ResponseEntity.badRequest().body(fail("缺少订单或申请人标识"));
        }

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(fail("订单不存在"));
        }
        if (!initiatorId.equals(order.getBuyerId())) {
            return ResponseEntity.badRequest().body(fail("只有买家可以申请平台介入"));
        }
        if (!OrderStateMachine.SHIPPED.equals(order.getStatus())) {
            return ResponseEntity.badRequest().body(fail(
                    "只有「已发货」的订单可以申请平台介入，当前状态是「" + order.getStatus() + "」"));
        }
        if (disputeRepository.existsByOrderIdAndStatus(orderId, Dispute.STATUS_PENDING)) {
            return ResponseEntity.badRequest().body(fail("这笔订单已有待处理的纠纷"));
        }

        String detail = text(body, "detail");
        if (detail.length() > MAX_DETAIL) {
            return ResponseEntity.badRequest().body(fail("说明不能超过 " + MAX_DETAIL + " 字"));
        }

        Dispute dispute = new Dispute();
        dispute.setOrderId(orderId);
        dispute.setGoodsId(order.getGoodsId());
        dispute.setGoodsTitle(order.getGoodsTitle());
        dispute.setInitiatorId(initiatorId);
        dispute.setRespondentId(order.getSellerId());
        dispute.setReason(text(body, "reason").isEmpty() ? "其他" : text(body, "reason"));
        dispute.setDetail(detail);
        disputeRepository.save(dispute);

        // 订单进入仲裁中：货款继续冻结在平台，等判定
        applyOrderStatus(order, OrderStateMachine.DISPUTED);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("disputeId", dispute.getId());
        result.put("orderStatus", order.getStatus());
        result.put("message", "已提交平台介入，货款将冻结至裁决完成");
        return ResponseEntity.ok(result);
    }

    // 我发起或收到的纠纷
    @GetMapping
    public ResponseEntity<?> list(@RequestParam Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", view(disputeRepository
                .findByInitiatorIdOrRespondentIdOrderByCreatedAtDesc(userId, userId)));
        return ResponseEntity.ok(result);
    }

    // 管理端：待处理纠纷
    @GetMapping("/pending")
    public ResponseEntity<?> pending(@RequestParam Long handlerId) {
        User handler = userRepository.findById(handlerId).orElse(null);
        if (handler == null || !ROLE_ADMIN.equals(handler.getRole())) {
            return ResponseEntity.badRequest().body(fail("只有平台管理员可以查看待处理纠纷"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", view(disputeRepository
                .findByStatusOrderByCreatedAtAsc(Dispute.STATUS_PENDING)));
        result.put("handledList", view(disputeRepository
                .findByStatusOrderByHandledAtDesc(Dispute.STATUS_HANDLED)));
        result.put("handled", disputeRepository.countByStatus(Dispute.STATUS_HANDLED));
        return ResponseEntity.ok(result);
    }

    // 管理端：裁定
    @PostMapping("/{id}/rule")
    @Transactional
    public ResponseEntity<?> rule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long handlerId = longOf(body, "handlerId");
        User handler = handlerId == null ? null : userRepository.findById(handlerId).orElse(null);
        if (handler == null || !ROLE_ADMIN.equals(handler.getRole())) {
            return ResponseEntity.badRequest().body(fail("只有平台管理员可以裁定纠纷"));
        }

        Dispute dispute = disputeRepository.findById(id).orElse(null);
        if (dispute == null) {
            return ResponseEntity.badRequest().body(fail("纠纷不存在"));
        }
        if (!Dispute.STATUS_PENDING.equals(dispute.getStatus())) {
            return ResponseEntity.badRequest().body(fail("这条纠纷已经裁定过了"));
        }

        String ruling = text(body, "ruling");
        String target;
        if (Dispute.RULING_BUYER.equals(ruling)) {
            target = OrderStateMachine.REFUNDED;     // 支持买家：货款退回
        } else if (Dispute.RULING_SELLER.equals(ruling)) {
            target = OrderStateMachine.FINISHED;     // 驳回诉求：视为完成，货款放给卖家
        } else {
            return ResponseEntity.badRequest().body(
                    fail("裁定结果只能是「" + Dispute.RULING_BUYER + "」或「" + Dispute.RULING_SELLER + "」"));
        }

        Order order = orderRepository.findById(dispute.getOrderId()).orElse(null);
        if (order == null) {
            return ResponseEntity.badRequest().body(fail("关联订单已不存在"));
        }
        if (!OrderStateMachine.canTransfer(order.getStatus(), target)) {
            return ResponseEntity.badRequest().body(fail(
                    "订单当前状态「" + order.getStatus() + "」不允许该裁定：" 
                            + OrderStateMachine.describeNext(order.getStatus())));
        }

        dispute.setStatus(Dispute.STATUS_HANDLED);
        dispute.setRuling(ruling);
        dispute.setRulingNote(text(body, "note"));
        dispute.setHandlerId(handlerId);
        dispute.setHandledAt(LocalDateTime.now());
        disputeRepository.save(dispute);

        applyOrderStatus(order, target);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("orderStatus", order.getStatus());
        result.put("escrowStatus", order.getEscrowStatus());
        result.put("message", Dispute.RULING_BUYER.equals(ruling)
                ? "已裁定支持买家，货款退回买家，商品重新上架"
                : "已驳回买家诉求，交易完成，货款放给卖家");
        return ResponseEntity.ok(result);
    }

    /** 复用状态机的资金规则，避免仲裁另起一套状态。 */
    private void applyOrderStatus(Order order, String target) {
        order.setStatus(target);
        order.setEscrowStatus(OrderStateMachine.escrowOf(target));
        LocalDateTime now = LocalDateTime.now();
        if (OrderStateMachine.FINISHED.equals(target)) {
            order.setSettledAt(now);
        }
        orderRepository.save(order);

        if (OrderStateMachine.REFUNDED.equals(target)) {
            goodsRepository.findById(order.getGoodsId()).ifPresent(g -> {
                if (!"在售".equals(g.getStatus())) {
                    g.setStatus("在售");
                    goodsRepository.save(g);
                }
            });
        }
    }

    /** 补上双方昵称，前端不用再查一次。 */
    private List<Map<String, Object>> view(List<Dispute> disputes) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Dispute dispute : disputes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", dispute.getId());
            item.put("orderId", dispute.getOrderId());
            item.put("goodsId", dispute.getGoodsId());
            item.put("goodsTitle", dispute.getGoodsTitle());
            item.put("reason", dispute.getReason());
            item.put("detail", dispute.getDetail());
            item.put("status", dispute.getStatus());
            item.put("ruling", dispute.getRuling());
            item.put("rulingNote", dispute.getRulingNote());
            item.put("createdAt", dispute.getCreatedAt());
            item.put("handledAt", dispute.getHandledAt());
            item.put("initiatorName", nameOf(dispute.getInitiatorId(), "买家"));
            item.put("respondentName", nameOf(dispute.getRespondentId(), "卖家"));
            item.put("handlerName", dispute.getHandlerId() == null
                    ? "" : nameOf(dispute.getHandlerId(), "平台"));
            items.add(item);
        }
        return items;
    }

    private String nameOf(Long userId, String fallback) {
        return userRepository.findById(userId).map(User::getUsername).orElse(fallback);
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
