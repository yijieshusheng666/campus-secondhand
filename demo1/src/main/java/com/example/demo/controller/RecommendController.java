package com.example.demo.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.entity.Goods;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.web.ApiResponses;

/**
 * 兜底关键词推荐（A1）。
 *
 * <p>AI 服务可用时，前端走 {@code /api/ai/search} 做语义搜索；AI 不可用或召回为空
 * 时再回到这个本地关键词兜底匹配，保证搜索永远可用。响应保持
 * {@code {success, data: [...]}} 与 AI 接口同形，前端调用方不用关心走的是哪条路径。</p>
 */
@RestController
@RequestMapping("/api")
public class RecommendController {

    @Autowired
    private GoodsRepository goodsRepository;

    @PostMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(@RequestBody Map<String, String> request) {
        String keyword = request.get("keyword");
        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.ok(ApiResponses.fail("请输入搜索关键词"));
        }
        keyword = keyword.trim();

        // 去掉口语化字词提取核心词，如"我想买一副耳机"→"耳机"
        String cleaned = keyword.replaceAll(
                "[我想买求要帮找请问需要一副台件部只个本有没在吗呢啊了吧哦了是与和还,，。！!？?~～\\s]", "");

        List<Goods> allGoods = goodsRepository.findAll();
        List<Map<String, Object>> results = new ArrayList<>();

        for (Goods goods : allGoods) {
            // 已售商品不再推荐
            if ("已售".equals(goods.getStatus())) continue;
            int score = 0;
            String title = goods.getTitle() != null ? goods.getTitle() : "";
            String category = goods.getCategory() != null ? goods.getCategory() : "";

            // 完全匹配标题加分最多
            if (title.contains(keyword)) score += 10;
            // 匹配分类加分
            if (category.contains(keyword)) score += 5;

            // 核心词匹配
            if (!cleaned.isEmpty() && !cleaned.equals(keyword)) {
                if (title.contains(cleaned)) score += 8;
                if (category.contains(cleaned)) score += 4;
            }

            // 中文长句兜底：2 字滑窗切分，自然语句也能命中商品
            String source = cleaned.isEmpty() ? keyword : cleaned;
            if (source.length() > 2) {
                for (int i = 0; i + 2 <= source.length(); i++) {
                    String gram = source.substring(i, i + 2);
                    if (title.contains(gram)) score += 2;
                    if (category.contains(gram)) score += 1;
                }
            }

            // 英文/多词输入：按空格拆词匹配
            for (String word : keyword.split("[\\s,，]+")) {
                if (word.isEmpty()) continue;
                if (title.contains(word)) score += 3;
                if (category.contains(word)) score += 2;
            }

            if (score > 0) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", goods.getId());
                item.put("title", goods.getTitle());
                item.put("price", goods.getPrice());
                item.put("category", goods.getCategory());
                item.put("score", score);
                results.add(item);
            }
        }

        // 按匹配分数降序排序
        results.sort((a, b) -> ((Integer) b.get("score")).compareTo((Integer) a.get("score")));

        // 最多返回 5 条
        if (results.size() > 5) {
            results = new ArrayList<>(results.subList(0, 5));
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("data", results);
        return ResponseEntity.ok(ApiResponses.ok("ok", extras));
    }
}
