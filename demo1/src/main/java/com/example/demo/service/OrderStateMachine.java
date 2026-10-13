package com.example.demo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 担保交易状态机（B2）。
 *
 * 与普通二手交易的区别：**货款先托管在平台，买家确认收货后才放款给卖家**。
 * 所有订单状态流转都必须经过这里校验，避免出现「待付款直接发货」这类非法跳转。
 *
 * 状态与资金状态是两条线，一一对应：
 * <pre>
 *   待付款 --付款(买家)--> 待发货 --发货(卖家)--> 已发货 --确认收货(买家)--> 已完成
 *     |                     |                    |                          （放款给卖家）
 *     +--取消(买家)--> 已取消  +----申请退款(买家)----> 退款中 --同意退款(卖家)--> 已退款
 *                          （以上三个状态资金都处于「平台托管」）              （退回买家）
 * </pre>
 *
 * 边界说明：本阶段只覆盖正常流程；「卖家拒绝退款 → 平台介入」属于纠纷仲裁（B4），
 * 因此这里 退款中 只能走向 已退款，不提供「拒绝退款」回退。
 */
public final class OrderStateMachine {

    // ---- 订单状态 ----
    public static final String PENDING_PAY = "待付款";
    public static final String PAID = "待发货";
    public static final String SHIPPED = "已发货";
    public static final String FINISHED = "已完成";
    public static final String REFUNDING = "退款中";
    public static final String REFUNDED = "已退款";
    public static final String CANCELLED = "已取消";
    /** B4 纠纷仲裁中：买卖双方谈不拢，货款继续锁在平台等判定 */
    public static final String DISPUTED = "仲裁中";

    // ---- 资金状态 ----
    public static final String ESCROW_UNPAID = "未付款";
    public static final String ESCROW_HELD = "平台托管";
    public static final String ESCROW_RELEASED = "已放款";
    public static final String ESCROW_RETURNED = "已退回";

    // ---- 操作方 ----
    public static final String ACTOR_BUYER = "buyer";
    public static final String ACTOR_SELLER = "seller";
    /** B4：平台管理员，纠纷仲裁的唯一裁决方 */
    public static final String ACTOR_ADMIN = "admin";

    private static final Map<String, Set<String>> TRANSITIONS;
    private static final Map<String, String> ESCROW_OF;
    private static final Map<String, String> ACTOR_OF;
    private static final Map<String, String> LABEL_OF;

    static {
        Map<String, Set<String>> transitions = new LinkedHashMap<>();
        transitions.put(PENDING_PAY, new java.util.LinkedHashSet<>(List.of(PAID, CANCELLED)));
        transitions.put(PAID, new java.util.LinkedHashSet<>(List.of(SHIPPED, REFUNDING)));
        transitions.put(SHIPPED, new java.util.LinkedHashSet<>(List.of(FINISHED, REFUNDING, DISPUTED)));
        // 卖家同意退款 -> 已退款；卖家拒绝退款 -> 退回已发货，买家不服可申请平台介入
        transitions.put(REFUNDING, new java.util.LinkedHashSet<>(List.of(REFUNDED, SHIPPED)));
        // 平台裁决只有两种结局：支持买家退款，或驳回诉求、视为交易完成并放款
        transitions.put(DISPUTED, new java.util.LinkedHashSet<>(List.of(REFUNDED, FINISHED)));
        // 终态：无后继
        transitions.put(FINISHED, new java.util.LinkedHashSet<>());
        transitions.put(REFUNDED, new java.util.LinkedHashSet<>());
        transitions.put(CANCELLED, new java.util.LinkedHashSet<>());
        TRANSITIONS = transitions;

        Map<String, String> escrow = new LinkedHashMap<>();
        escrow.put(PENDING_PAY, ESCROW_UNPAID);
        escrow.put(PAID, ESCROW_HELD);
        escrow.put(SHIPPED, ESCROW_HELD);
        escrow.put(REFUNDING, ESCROW_HELD);
        // 争议期间货款继续冻结在平台，谁也不能动
        escrow.put(DISPUTED, ESCROW_HELD);
        escrow.put(FINISHED, ESCROW_RELEASED);
        escrow.put(REFUNDED, ESCROW_RETURNED);
        escrow.put(CANCELLED, ESCROW_UNPAID);
        ESCROW_OF = escrow;

        Map<String, String> actors = new LinkedHashMap<>();
        actors.put(key(PENDING_PAY, PAID), ACTOR_BUYER);
        actors.put(key(PENDING_PAY, CANCELLED), ACTOR_BUYER);
        actors.put(key(PAID, SHIPPED), ACTOR_SELLER);
        actors.put(key(PAID, REFUNDING), ACTOR_BUYER);
        actors.put(key(SHIPPED, FINISHED), ACTOR_BUYER);
        actors.put(key(SHIPPED, REFUNDING), ACTOR_BUYER);
        actors.put(key(SHIPPED, DISPUTED), ACTOR_BUYER);
        actors.put(key(REFUNDING, REFUNDED), ACTOR_SELLER);
        actors.put(key(REFUNDING, SHIPPED), ACTOR_SELLER);
        actors.put(key(DISPUTED, REFUNDED), ACTOR_ADMIN);
        actors.put(key(DISPUTED, FINISHED), ACTOR_ADMIN);
        ACTOR_OF = actors;

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(key(PENDING_PAY, PAID), "立即付款");
        labels.put(key(PENDING_PAY, CANCELLED), "取消订单");
        labels.put(key(PAID, SHIPPED), "标记发货");
        labels.put(key(PAID, REFUNDING), "申请退款");
        labels.put(key(SHIPPED, FINISHED), "确认收货");
        labels.put(key(SHIPPED, REFUNDING), "申请退款");
        labels.put(key(SHIPPED, DISPUTED), "申请平台介入");
        labels.put(key(REFUNDING, REFUNDED), "同意退款");
        labels.put(key(REFUNDING, SHIPPED), "拒绝退款");
        labels.put(key(DISPUTED, REFUNDED), "裁定：支持买家退款");
        labels.put(key(DISPUTED, FINISHED), "裁定：驳回诉求并放款");
        LABEL_OF = labels;
    }

    private OrderStateMachine() {
    }

    private static String key(String from, String to) {
        return from + "->" + to;
    }

    public static boolean isKnown(String status) {
        return TRANSITIONS.containsKey(status);
    }

    public static boolean canTransfer(String from, String to) {
        Set<String> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static Set<String> nextStates(String from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    /** 该流转应由谁发起：buyer / seller；非法流转返回空串。 */
    public static String actorOf(String from, String to) {
        return ACTOR_OF.getOrDefault(key(from, to), "");
    }

    public static String escrowOf(String status) {
        return ESCROW_OF.getOrDefault(status, ESCROW_UNPAID);
    }

    /**
     * 前端可直接渲染的操作按钮：[{status, label, actor}]。
     * 订单 JSON 里带出这个字段，前端就不用把状态规则再抄一遍。
     */
    public static List<Map<String, String>> nextActions(String from) {
        List<Map<String, String>> actions = new ArrayList<>();
        for (String to : nextStates(from)) {
            Map<String, String> action = new LinkedHashMap<>();
            action.put("status", to);
            action.put("label", LABEL_OF.getOrDefault(key(from, to), to));
            action.put("actor", actorOf(from, to));
            actions.add(action);
        }
        return actions;
    }

    /** 非法流转时的提示文案。 */
    public static String describeNext(String from) {
        Set<String> allowed = nextStates(from);
        if (allowed.isEmpty()) {
            return "「" + from + "」是终态，不能再流转";
        }
        return "「" + from + "」只能流转到：" + String.join(" / ", allowed);
    }
}
