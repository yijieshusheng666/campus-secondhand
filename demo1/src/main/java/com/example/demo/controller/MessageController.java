package com.example.demo.controller;

import java.util.ArrayList;
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

import com.example.demo.auth.AuthContext;
import com.example.demo.entity.Goods;
import com.example.demo.entity.Message;
import com.example.demo.entity.User;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.repository.MessageRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.web.ApiResponses;
import com.example.demo.web.RequestUtils;

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

    // 发送一条消息。发件人取自 token：从前读 body 里的 fromUserId，等于可以冒名给别人发消息
    @PostMapping
    public ResponseEntity<?> send(@RequestBody Map<String, Object> body) {
        Long goodsId = RequestUtils.longOf(body, "goodsId");
        Long fromUserId = AuthContext.requireUserId();
        Long toUserId = RequestUtils.longOf(body, "toUserId");
        String content = RequestUtils.textOf(body, "content");
        if (goodsId == null || toUserId == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("缺少必要的消息标识"));
        }
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("消息内容不能为空"));
        }
        if (fromUserId.equals(toUserId)) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("不能给自己发送消息"));
        }

        Goods goods = goodsRepository.findById(goodsId).orElse(null);
        if (goods == null) {
            return ResponseEntity.badRequest().body(ApiResponses.fail("商品不存在"));
        }

        Message msg = new Message();
        msg.setGoodsId(goodsId);
        msg.setGoodsTitle(goods.getTitle());
        msg.setFromUserId(fromUserId);
        msg.setToUserId(toUserId);
        msg.setContent(content);
        messageRepository.save(msg);

        return ResponseEntity.ok(ApiResponses.ok("已发送"));
    }

    // 查询某件商品下两个人的完整对话。"我"的一方取自 token，
    // 这样别人传任意 userId 也查不到不是自己参与的对话。
    @GetMapping
    public List<Message> conversation(@RequestParam Long goodsId,
                                      @RequestParam Long peerId) {
        Long userId = AuthContext.requireUserId();
        return messageRepository.findConversation(goodsId, userId, peerId);
    }

    // 我的会话列表：按（商品 + 对方）聚合，每个会话只保留最新一条消息
    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations() {
        Long userId = AuthContext.requireUserId();
        List<Message> all = messageRepository.findByFromUserIdOrToUserIdOrderByCreatedAtDesc(userId, userId);

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (Message m : all) {
            Long peerId = m.getFromUserId().equals(userId) ? m.getToUserId() : m.getFromUserId();
            String key = m.getGoodsId() + "-" + peerId;
            // 列表已按时间倒序，同一个会话保留第一条（最新）即可
            if (grouped.containsKey(key)) continue;

            Map<String, Object> item = new LinkedHashMap<>();
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
