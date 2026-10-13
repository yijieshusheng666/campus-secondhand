package com.example.demo.service;

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
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("top_k", topK);

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
