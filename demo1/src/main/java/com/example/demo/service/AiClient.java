package com.example.demo.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 调用 Python AI 服务的客户端。
 * 设计原则：任何异常都吞掉并返回空结果，AI 服务不可用时业务退化为关键词搜索。
 */
@Component
public class AiClient {

    private static final Logger logger = LoggerFactory.getLogger(AiClient.class);

    /** 客服问答注入的上下文条数：太多会把无关片段塞进 Prompt。 */
    private static final int CHAT_TOP_K = 4;

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public AiClient(@Value("${ai.service.url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.restTemplate = new RestTemplate(factory);
    }

    /** 语义搜索，返回 [{goodsId, score}]；失败返回空列表。 */
    public List<Map<String, Object>> search(String query, int topK) {
        return search(query, topK, null);
    }

    /**
     * 语义搜索（可指定最低相似度）。
     * minScore 传 null 时用 AI 服务按模式标定的默认阈值；
     * 求购撮合这类"发现"场景会显式传 0，改为在 Java 侧按相对分排序取头部。
     */
    public List<Map<String, Object>> search(String query, int topK, Double minScore) {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("top_k", topK);
            if (minScore != null) {
                body.put("min_score", minScore);
            }

            JSONObject resp = post("/ai/search", body);
            if (resp == null) {
                return results;
            }
            JSONArray arr = resp.getJSONArray("results");
            if (arr == null) {
                return results;
            }
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                Map<String, Object> hit = new HashMap<>();
                hit.put("goodsId", item.getLong("goodsId"));
                hit.put("score", item.getDouble("score"));
                results.add(hit);
            }
        } catch (Exception e) {
            logger.warn("语义搜索调用失败，将退化为关键词搜索：{}", e.getMessage());
        }
        return results;
    }

    /** 增量更新单件商品的索引；失败静默。 */
    public void indexGoods(Long goodsId, String text) {
        try {
            Map<String, Object> item = new HashMap<>();
            item.put("goodsId", goodsId);
            item.put("text", text);
            Map<String, Object> body = new HashMap<>();
            body.put("items", List.of(item));
            post("/ai/index", body);
        } catch (Exception e) {
            logger.warn("商品索引推送失败 goodsId={}：{}", goodsId, e.getMessage());
        }
    }

    /** 全量重建索引；返回是否成功。 */
    public boolean rebuildIndex(List<Map<String, Object>> items) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("items", items);
            JSONObject resp = post("/ai/index/rebuild", body);
            return resp != null && Boolean.TRUE.equals(resp.getBoolean("success"));
        } catch (Exception e) {
            logger.warn("索引重建失败：{}", e.getMessage());
            return false;
        }
    }

    /** RAG 问答：返回 AI 服务原始响应（answer / mode / sources）；失败返回 null。 */
    public JSONObject chat(String question, List<Map<String, String>> history) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("question", question);
            body.put("history", history);
            body.put("top_k", CHAT_TOP_K);
            return post("/ai/chat", body);
        } catch (Exception e) {
            logger.warn("RAG 问答调用失败，前端将回退关键词客服：{}", e.getMessage());
            return null;
        }
    }

    /** 对话摘要（A5）；失败返回 null。 */
    public JSONObject summarize(List<Map<String, String>> messages, String goodsTitle, BigDecimal goodsPrice) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("messages", messages);
            body.put("goodsTitle", goodsTitle == null ? "" : goodsTitle);
            if (goodsPrice != null) {
                body.put("goodsPrice", goodsPrice);
            }
            return post("/ai/summarize", body);
        } catch (Exception e) {
            logger.warn("对话摘要调用失败：{}", e.getMessage());
            return null;
        }
    }

    /** 议价话术（A5）；失败返回 null。 */
    public JSONObject negotiate(String goodsTitle, BigDecimal goodsPrice, String description) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("goodsTitle", goodsTitle == null ? "" : goodsTitle);
            body.put("description", description == null ? "" : description);
            if (goodsPrice != null) {
                body.put("goodsPrice", goodsPrice);
            }
            return post("/ai/negotiate", body);
        } catch (Exception e) {
            logger.warn("议价话术调用失败：{}", e.getMessage());
            return null;
        }
    }

    private JSONObject post(String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request =
                new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> resp =
                restTemplate.postForEntity(baseUrl + path, request, String.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            return null;
        }
        return JSON.parseObject(resp.getBody());
    }
}
