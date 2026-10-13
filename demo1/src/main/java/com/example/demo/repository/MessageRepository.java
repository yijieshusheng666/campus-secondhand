package com.example.demo.repository;

import com.example.demo.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 某件商品下两个用户之间的完整对话（双向），按时间正序
    @Query("select m from Message m where m.goodsId = :goodsId and " +
            "((m.fromUserId = :a and m.toUserId = :b) or (m.fromUserId = :b and m.toUserId = :a)) " +
            "order by m.createdAt asc")
    List<Message> findConversation(@Param("goodsId") Long goodsId,
                                   @Param("a") Long a,
                                   @Param("b") Long b);

    // 我参与过的全部消息，用于汇总会话列表
    List<Message> findByFromUserIdOrToUserIdOrderByCreatedAtDesc(Long fromUserId, Long toUserId);
}