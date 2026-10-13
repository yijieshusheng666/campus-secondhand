package com.example.demo.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Goods;
import com.example.demo.entity.Message;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.MessageRepository;
import com.example.demo.repository.UserRepository;

// 买家与卖家沟通：围绕某件商品的一对一消息
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private UserRepository userRepository;

    // 发送一条消息
    @PostMapping
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body) {
        Long goodsId = Long.valueOf(body.get("goodsId").toString());
        Long fromUserId = Long.valueOf(body.get("fromUserId").toString());
        Long toUserId = Long.valueOf(body.get("toUserId").toString());
        String content = body.get("content") == null ? "" : body.get("content").toString().trim();

        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "消息内容不能为空"));
        }
        if (fromUserId.equals(toUserId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "不能给自己发送消息"));
        }

        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "商品不存在"));
        }

        Message msg = new Message();
        msg.setGoodsId(goodsId);
        msg.setGoodsTitle(goods.getTitle());
        msg.setFromUserId(fromUserId);
        msg.setToUserId(toUserId);
        msg.setContent(content);
        messageRepository.save(msg);

        return ResponseEntity.ok(Map.of("success", true, "message", "已发送"));
    }

    // 查询某件商品下两个人的完整对话
    @GetMapping
    public List<Message> conversation(@RequestParam Long goodsId,
                                      @RequestParam Long userId,
                                      @RequestParam Long peerId) {
        return messageRepository.findConversation(goodsId, userId, peerId);
    }

    // 我的会话列表：按（商品 + 对方）聚合，每个会话只保留最新一条消息
    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@RequestParam Long userId) {
        List<Message> all = messageRepository.findByFromUserIdOrToUserIdOrderByCreatedAtDesc(userId, userId);

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Message m : all) {
            Long peerId = m.getFromUserId().equals(userId) ? m.getToUserId() : m.getFromUserId();
            String key = m.getGoodsId() + "-" + peerId;
            // 列表已按时间倒序，同一个会话保留第一条（最新）即可
            if (grouped.containsKey(key)) continue;

            Map<String, Object> item = new HashMap<>();
            item.put("goodsId", m.getGoodsId());
            item.put("goodsTitle", m.getGoodsTitle());
            item.put("peerId", peerId);
            User peer = userRepository.findById(peerId).orElse(null);
            item.put("peerName", peer != null ? peer.getUsername() : "未知用户");
            item.put("lastContent", m.getContent());
            item.put("lastTime", m.getCreatedAt());
            grouped.put(key, item);
        }
        return new ArrayList<>(grouped.values());
    }
}