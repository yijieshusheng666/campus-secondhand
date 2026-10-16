package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 担保交易状态机的纯函数测试（不依赖 Spring 上下文与数据库）。
 *
 * <p>这是全项目最值得做单测的一块：它有资金语义，一旦流转或操作方判断出错，
 * 后果是「任何人能把货款判给任意一方」。</p>
 */
class OrderStateMachineTest {

    @Test
    void legalTransitionsAreAccepted() {
        assertTrue(OrderStateMachine.canTransfer(OrderStateMachine.PENDING_PAY, OrderStateMachine.PAID));
        assertTrue(OrderStateMachine.canTransfer(OrderStateMachine.PAID, OrderStateMachine.SHIPPED));
        assertTrue(OrderStateMachine.canTransfer(OrderStateMachine.SHIPPED, OrderStateMachine.FINISHED));
        assertTrue(OrderStateMachine.canTransfer(OrderStateMachine.REFUNDING, OrderStateMachine.REFUNDED));
        assertTrue(OrderStateMachine.canTransfer(OrderStateMachine.DISPUTED, OrderStateMachine.REFUNDED));
    }

    @Test
    void illegalTransitionsAreRejected() {
        // 待付款不能直接发货，也不能跳过流程直接完成
        assertFalse(OrderStateMachine.canTransfer(OrderStateMachine.PENDING_PAY, OrderStateMachine.SHIPPED));
        assertFalse(OrderStateMachine.canTransfer(OrderStateMachine.PENDING_PAY, OrderStateMachine.FINISHED));
        // 终态没有后继
        for (String terminal : List.of(OrderStateMachine.FINISHED, OrderStateMachine.REFUNDED,
                OrderStateMachine.CANCELLED)) {
            assertTrue(OrderStateMachine.nextStates(terminal).isEmpty(), terminal + " 应该是终态");
        }
        // 不能从已取消回到任何状态
        assertFalse(OrderStateMachine.canTransfer(OrderStateMachine.CANCELLED, OrderStateMachine.PAID));
    }

    @Test
    void everyTransitionHasAnActorDefinition() {
        // 这条断言固化的是静态初始化里的自检：漏一个 ACTOR_OF 条目，
        // 订单接口里那两个 if 就一个都不进，等于该流转变成任何人可发起。
        for (String from : List.of(OrderStateMachine.PENDING_PAY, OrderStateMachine.PAID,
                OrderStateMachine.SHIPPED, OrderStateMachine.REFUNDING, OrderStateMachine.DISPUTED)) {
            for (String to : OrderStateMachine.nextStates(from)) {
                assertNotEquals("", OrderStateMachine.actorOf(from, to),
                        "流转 " + from + "→" + to + " 缺少操作方定义");
            }
        }
    }

    @Test
    void actorOfReturnsEmptyForUnknownTransition() {
        // 空串的语义是「拒绝」，不是「不校验」——调用方必须据此拦下
        assertEquals("", OrderStateMachine.actorOf(OrderStateMachine.PENDING_PAY, OrderStateMachine.FINISHED));
        assertEquals("", OrderStateMachine.actorOf("不存在的状态", OrderStateMachine.PAID));
    }

    @Test
    void disputeRulingBelongsToAdminOnly() {
        // 纠纷裁定是唯一的资金裁决入口，操作方必须是 admin
        assertEquals(OrderStateMachine.ACTOR_ADMIN,
                OrderStateMachine.actorOf(OrderStateMachine.DISPUTED, OrderStateMachine.REFUNDED));
        assertEquals(OrderStateMachine.ACTOR_ADMIN,
                OrderStateMachine.actorOf(OrderStateMachine.DISPUTED, OrderStateMachine.FINISHED));
    }

    @Test
    void escrowIsFrozenWhileDisputed() {
        // 争议期间货款必须锁在平台，谁也不能动
        assertEquals(OrderStateMachine.ESCROW_HELD, OrderStateMachine.escrowOf(OrderStateMachine.DISPUTED));
        assertEquals(OrderStateMachine.ESCROW_HELD, OrderStateMachine.escrowOf(OrderStateMachine.REFUNDING));
        assertEquals(OrderStateMachine.ESCROW_UNPAID, OrderStateMachine.escrowOf(OrderStateMachine.PENDING_PAY));
        assertEquals(OrderStateMachine.ESCROW_RELEASED, OrderStateMachine.escrowOf(OrderStateMachine.FINISHED));
        assertEquals(OrderStateMachine.ESCROW_RETURNED, OrderStateMachine.escrowOf(OrderStateMachine.REFUNDED));
    }

    @Test
    void nextActionsCarryActorForTheFrontend() {
        // 前端直接渲染这个列表出按钮，actor 字段必须完整，否则按钮点下去才被拒
        List<Map<String, String>> actions = OrderStateMachine.nextActions(OrderStateMachine.SHIPPED);
        assertFalse(actions.isEmpty());
        for (Map<String, String> action : actions) {
            assertNotEquals("", action.get("actor"), "按钮 " + action.get("label") + " 缺少操作方");
            assertNotEquals("", action.get("label"));
        }
    }
}
