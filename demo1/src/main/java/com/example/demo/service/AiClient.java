package com.example.demo.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import org.springframework.http.HttpMethod;
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
    /**
     * SSE 专用：读超时设为 0（永不超时）。
     * 普通 JSON 调用要 25 秒上限防止 AI 服务卡死拖垮线程池；
     * 但流式回答本身就可能长时间没有新数据（LLM 慢思考），
     * 用同一个 25 秒会在回答中途被 SocketTimeout 掐断。
     */
    private final RestTemplate streamRestTemplate;
    private final String baseUrl;

    public AiClient(@Value("${ai.service.url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(25));
        this.restTemplate = new RestTemplate(factory);

        SimpleClientHttpRequestFactory streamFactory = new SimpleClientHttpRequestFactory();
        streamFactory.setConnectTimeout(Duration.ofSeconds(2));
        streamFactory.setReadTimeout(Duration.ZERO);
        this.streamRestTemplate = new RestTemplate(streamFactory);
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

    /** 内容风控（A6）；失败返回 null，由调用方决定是否放行。 */
    public JSONObject moderate(String title, String description) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", title == null ? "" : title);
            body.put("description", description == null ? "" : description);
            return post("/ai/moderate", body);
        } catch (Exception e) {
            logger.warn("内容风控调用失败，按放行处理：{}", e.getMessage());
            return null;
        }
    }

    /** 图片识别自动发布（A3）；失败返回 null。 */
    public JSONObject vision(String imageBase64, String mime) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("image", imageBase64);
            body.put("mime", mime == null || mime.isBlank() ? "image/jpeg" : mime);
            return post("/ai/vision", body);
        } catch (Exception e) {
            logger.warn("图片识别调用失败：{}", e.getMessage());
            return null;
        }
    }

    /** 通用 JSON 转发（GET/POST/DELETE），供运营端等接口复用。失败返回 null。 */
    public JSONObject json(HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request =
                    body == null ? new HttpEntity<>(headers)
                            : new HttpEntity<>(JSON.toJSONString(body), headers);
            ResponseEntity<String> resp = restTemplate.exchange(
                    java.net.URI.create(baseUrl + path), method, request, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            return JSON.parseObject(resp.getBody());
        } catch (Exception e) {
            logger.warn("转发 {} {} 失败：{}", method, path, e.getMessage());
            return null;
        }
    }

    /** 取文本响应（CSV 导出等非 JSON 接口用）。 */
    public String text(String path) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(baseUrl + path, String.class);
            return resp.getBody();
        } catch (Exception e) {
            logger.warn("取文本 {} 失败：{}", path, e.getMessage());
            return null;
        }
    }

    /** 取字节响应（CSV / 文件下载等二进制或大文本响应；返回 raw bytes）。 */
    public byte[] bytes(String path) {
        try {
            ResponseEntity<byte[]> resp = restTemplate.getForEntity(baseUrl + path, byte[].class);
            return resp.getBody();
        } catch (Exception e) {
            logger.warn("取字节 {} 失败：{}", path, e.getMessage());
            return null;
        }
    }

    /**
     * SSE 转发：把 AI 服务的流式响应逐行透传给前端，不做缓冲，
     * 保证前端能拿到「首字」而不是等整段生成完。
     *
     * 实现要点：Spring 6.x 的 RestTemplate.execute(...) 第三参是 RequestCallback（函数式接口），
     * 它的内部类 HttpEntityRequestCallback 是 package-private 不能直接 new；用 lambda
     * 把 HttpEntity 的 headers/body 写到 ClientHttpRequest 输出流，比 RestTemplate.exchange
     * 更细粒度，能拿到原始 InputStream 用于 SSE 行级 flush。
     */
    public void streamChat(Object body, java.io.OutputStream out) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        final byte[] payload = JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8);

        org.springframework.web.client.ResponseExtractor<Void> extractor = response -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // ⚠️ 空行不能丢：SSE 用「空行」作为事件边界，
                    // 浏览器 EventSource / fetch 流解析都靠它派发事件。
                    // 之前这里写 if (line.isEmpty()) continue，把 \n\n 压成 \n，
                    // 结果前端一个事件都收不到（实测 38 个事件全糊在一起）。
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            return null;
        };

        org.springframework.web.client.RequestCallback callback = req -> {
            // 1. 把 HttpHeaders 拷到 ClientHttpRequest（包含 Content-Type 等）
            headers.forEach((k, v) -> req.getHeaders().addAll(k, v));
            // 2. 把 body 写入输出流，并显式 flush，避免缓冲区卡住
            req.getBody().write(payload);
            req.getBody().flush();
        };

        try {
            streamRestTemplate.execute(baseUrl + "/ai/chat/stream", HttpMethod.POST, callback, extractor);
        } catch (Exception e) {
            // 不能让异常冒泡：StreamingResponseBody 已经提交了 text/event-stream 响应头，
            // 此时再抛异常 Spring 会试图往 SSE 流里写 JSON，抛
            // HttpMessageNotWritableException（前端只看到 HTTP 500 + 空 body）。
            // 正确做法：自己吞掉，转成一条 SSE done 事件（unavailable 模式），
            // 让前端按既有逻辑回退到关键词推荐 / 本地规则。
            logger.warn("SSE 流式转发失败，降级为不可用：{}", e.getMessage());
            String json = "{\"success\":false,\"available\":false,\"mode\":\"unavailable\","
                    + "\"message\":\"AI 服务不可用\"}";
            String event = "event: error\n"
                    + "data: " + json + "\n\n";
            try {
                out.write(event.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (java.io.IOException ignored) {
                // 前端已断开，无需再写
            }
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
