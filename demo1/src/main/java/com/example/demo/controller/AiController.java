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

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.entity.Goods;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.service.AiClient;

/**
 * AI 相关业务接口。语义召回在 Python 服务完成，过滤与回填在本层完成，
 * 保证价格、分类、在售状态以 MySQL 为唯一数据源。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final int OVER_FETCH_FACTOR = 3;

    @Autowired
    private AiClient aiClient;

    @Autowired
    private GoodsRepository goodsRepository;

    /** 语义搜索：关键词→语义召回→MySQL 回填→分类/价格/在售过滤。 */
    @GetMapping("/search")
    public ResponseEntity<?> semanticSearch(
            @RequestParam String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Double maxPrice,
            @RequestParam(defaultValue = "12") int topK) {

        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(Map.of("success", true, "semantic", false, "data", List.of()));
        }

        List<Map<String, Object>> hits = aiClient.search(keyword.trim(), topK * OVER_FETCH_FACTOR);
        if (hits.isEmpty()) {
            // AI 服务不可用或无召回：交由前端回退到关键词搜索
            return ResponseEntity.ok(Map.of("success", true, "semantic", false, "data", List.of()));
        }

        List<Goods> filtered = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            if (filtered.size() >= topK) {
                break;
            }
            Long goodsId = (Long) hit.get("goodsId");
            Goods goods = goodsRepository.findById(goodsId).orElse(null);
            if (goods == null) {
                continue;
            }
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            if (category != null && !category.isBlank() && !category.equals(goods.getCategory())) {
                continue;
            }
            if (maxPrice != null && goods.getPrice() != null
                    && goods.getPrice().doubleValue() > maxPrice) {
                continue;
            }
            filtered.add(goods);
        }

        return ResponseEntity.ok(Map.of("success", true, "semantic", true, "data", filtered));
    }

    /** 全量重建向量索引：把当前所有在售商品推送给 AI 服务。 */
    @PostMapping("/reindex")
    public ResponseEntity<?> reindex() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Goods goods : goodsRepository.findAll()) {
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("goodsId", goods.getId());
            item.put("text", buildIndexText(goods));
            items.add(item);
        }
        boolean ok = aiClient.rebuildIndex(items);
        return ResponseEntity.ok(Map.of(
                "success", ok,
                "indexed", ok ? items.size() : 0,
                "message", ok ? "索引重建完成" : "AI 服务不可用，索引未重建"));
    }

    /** 商品索引文本：标题 + 分类 + 描述，供 Python 侧向量化。 */
    public static String buildIndexText(Goods goods) {
        String title = goods.getTitle() == null ? "" : goods.getTitle();
        String category = goods.getCategory() == null ? "" : goods.getCategory();
        String description = goods.getDescription() == null ? "" : goods.getDescription();
        return (title + " " + category + " " + description).trim();
    }

    /**
     * RAG 客服问答：检索平台知识库 + 在售商品，再交给 AI 服务生成回答并附引用来源。
     * AI 服务不可用时返回 available=false，由前端回退到关键词客服。
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody(required = false) Map<String, Object> body) {
        Object rawQuestion = body == null ? null : body.get("question");
        String question = rawQuestion == null ? "" : rawQuestion.toString().trim();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);

        if (question.isEmpty()) {
            result.put("available", true);
            result.put("mode", "none");
            result.put("answer", "请告诉我你想问什么，比如「怎么退款」「有没有便宜的台灯」。");
            result.put("sources", new ArrayList<>());
            return ResponseEntity.ok(result);
        }

        JSONObject resp = aiClient.chat(question, extractHistory(body));
        if (resp == null) {
            result.put("available", false);
            result.put("mode", "unavailable");
            result.put("answer", "");
            result.put("sources", new ArrayList<>());
            return ResponseEntity.ok(result);
        }

        JSONArray sources = resp.getJSONArray("sources");
        String answer = resp.getString("answer");

        result.put("available", true);
        result.put("mode", resp.getString("mode") == null ? "unknown" : resp.getString("mode"));
        result.put("answer", answer == null ? "" : answer);
        result.put("sources", sources == null ? new JSONArray() : sources);
        return ResponseEntity.ok(result);
    }

    /** 把前端传来的多轮对话转成 AI 服务认识的 {role, content} 列表。 */
    private List<Map<String, String>> extractHistory(Map<String, Object> body) {
        List<Map<String, String>> history = new ArrayList<>();
        if (body == null || !(body.get("history") instanceof List<?> raw)) {
            return history;
        }
        for (Object item : raw) {
            if (item instanceof Map<?, ?> entry && entry.get("content") != null) {
                Map<String, String> turn = new HashMap<>();
                turn.put("role", entry.get("role") == null ? "user" : entry.get("role").toString());
                turn.put("content", entry.get("content").toString());
                history.add(turn);
            }
        }
        return history;
    }
}
