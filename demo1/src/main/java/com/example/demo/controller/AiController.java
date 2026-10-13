package com.example.demo.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.entity.Goods;
import com.example.demo.repository.GoodsRepository;
import com.example.demo.service.AiClient;
import com.example.demo.service.KeywordUtil;

/**
 * AI 相关业务接口。语义召回在 Python 服务完成，过滤与回填在本层完成，
 * 保证价格、分类、在售状态以 MySQL 为唯一数据源。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final int OVER_FETCH_FACTOR = 3;
    /** A4 定价建议：取多少件同类商品做价格样本 */
    private static final int PRICE_SAMPLE_SIZE = 12;
    /** 样本少于这个数就不给建议价，避免一条数据瞎猜 */
    private static final int MIN_PRICE_SAMPLES = 2;
    /** 定价样本的相对分下限：低于「最高分 × 该系数」的同类样本不参与统计 */
    private static final double PRICE_RELATIVE_FLOOR = 0.35;
    /** 定价补扫的关键词切分上限 */
    private static final int PRICE_GRAM_LIMIT = 20;

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

    /** 商品索引文本：标题 + 分类 + 描述 + 课程号，供 Python 侧向量化。 */
    public static String buildIndexText(Goods goods) {
        String title = goods.getTitle() == null ? "" : goods.getTitle();
        String category = goods.getCategory() == null ? "" : goods.getCategory();
        String description = goods.getDescription() == null ? "" : goods.getDescription();
        String courseCode = goods.getCourseCode() == null ? "" : goods.getCourseCode();
        return (title + " " + category + " " + description + " " + courseCode).trim();
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
        return extractTurns(body, "history");
    }

    private List<Map<String, String>> extractTurns(Map<String, Object> body, String key) {
        List<Map<String, String>> turns = new ArrayList<>();
        if (body == null || !(body.get(key) instanceof List<?> raw)) {
            return turns;
        }
        for (Object item : raw) {
            if (item instanceof Map<?, ?> entry && entry.get("content") != null) {
                Map<String, String> turn = new HashMap<>();
                turn.put("role", entry.get("role") == null ? "user" : entry.get("role").toString());
                turn.put("content", entry.get("content").toString());
                turns.add(turn);
            }
        }
        return turns;
    }

    /**
     * A5 对话摘要：总结与卖家的沟通，抽出价格、见面约定与待确认事项。
     * AI 服务不可用时返回 available=false。
     */
    @PostMapping("/summarize")
    public ResponseEntity<?> summarize(@RequestBody(required = false) Map<String, Object> body) {
        JSONObject resp = aiClient.summarize(
                extractTurns(body, "messages"),
                text(body, "goodsTitle"),
                decimal(body, "goodsPrice"));
        return ResponseEntity.ok(wrap(resp));
    }

    /**
     * A5 议价话术：按商品价格给三档可直接发送的讲价话术。
     * AI 服务不可用时返回 available=false。
     */
    @PostMapping("/negotiate")
    public ResponseEntity<?> negotiate(@RequestBody(required = false) Map<String, Object> body) {
        JSONObject resp = aiClient.negotiate(
                text(body, "goodsTitle"),
                decimal(body, "goodsPrice"),
                text(body, "description"));
        return ResponseEntity.ok(wrap(resp));
    }

    /**
     * A6 内容风控：检查标题与描述。规则层永远可用，配了 LLM 会叠加模型判断。
     * 发布商品接口也会独立调用一次，前端绕过也拦得住。
     */
    @PostMapping("/moderate")
    public ResponseEntity<?> moderate(@RequestBody(required = false) Map<String, Object> body) {
        JSONObject resp = aiClient.moderate(text(body, "title"), text(body, "description"));
        return ResponseEntity.ok(wrap(resp));
    }

    /**
     * A3 图片识别自动发布：把图片交给多模态模型，返回预填的标题/分类/成色/描述。
     * 未配置多模态模型时返回 available=false，前端提示手动填写。
     */
    @PostMapping("/vision")
    public ResponseEntity<?> vision(@RequestBody(required = false) Map<String, Object> body) {
        JSONObject resp = aiClient.vision(text(body, "image"), text(body, "mime"));
        return ResponseEntity.ok(wrap(resp));
    }

    /**
     * A4 智能定价建议：用语义检索找到同类商品，按价格分布给建议价。
     * 只在 Java 侧编排，不新增 AI 接口 —— 价格以 MySQL 为唯一数据源。
     */
    @GetMapping("/price-suggest")
    public ResponseEntity<?> priceSuggest(@RequestParam String title,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);

        if (title == null || title.isBlank()) {
            result.put("available", false);
            result.put("message", "先填商品标题，我再帮你估个价");
            return ResponseEntity.ok(result);
        }

        // 注意：**不要把分类拼进检索词**。分类名出现在同类目每件商品的文案里，
        // 拼进去会让整个类目互相"相似"（实测「索尼降噪耳机 电子产品」把 iPhone、
        // MacBook 都拉进样本来，建议价被抬到 ¥6499）。分类只用于兜底补齐。
        String sampleQuery = title.trim();

        List<Map<String, Object>> hits = aiClient.search(sampleQuery, PRICE_SAMPLE_SIZE * 2, 0.0);
        double best = 0;
        for (Map<String, Object> hit : hits) {
            if (hit.get("score") instanceof Number number) {
                best = Math.max(best, number.doubleValue());
            }
        }
        double floor = best * PRICE_RELATIVE_FLOOR;

        // 候选 = 语义召回（只覆盖在售）+ Java 侧关键词扫描（覆盖全部商品）。
        // 必须补后一步：定价要参考"已售/历史"的价格，而 AI 索引里只有在售商品。
        // 实测「索尼降噪耳机」只召回 1 件（AirPods 已售、不在索引里），补扫后才有 2 件同类样本。
        Map<Long, Double> candidateScores = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            Object score = hit.get("score");
            double value = score instanceof Number number ? number.doubleValue() : 0;
            if (value >= floor && value > 0 && hit.get("goodsId") instanceof Number id) {
                candidateScores.put(id.longValue(), value);
            }
        }
        List<String> grams = KeywordUtil.grams(sampleQuery, PRICE_GRAM_LIMIT);
        for (Goods goods : goodsRepository.findAll()) {
            if (goods.getPrice() == null || grams.isEmpty()) {
                continue;
            }
            String text = (goods.getTitle() == null ? "" : goods.getTitle())
                    + (goods.getCategory() == null ? "" : goods.getCategory())
                    + (goods.getDescription() == null ? "" : goods.getDescription());
            int hitCount = 0;
            for (String gram : grams) {
                if (text.contains(gram)) {
                    hitCount++;
                }
            }
            if (hitCount > 0) {
                double keywordScore = hitCount / (double) grams.size();
                candidateScores.merge(goods.getId(), keywordScore, Math::max);
            }
        }

        List<Long> ranked = new ArrayList<>(candidateScores.keySet());
        ranked.sort((a, b) -> Double.compare(candidateScores.get(b), candidateScores.get(a)));

        Map<Long, Goods> samples = new LinkedHashMap<>();
        for (Long id : ranked) {
            if (samples.size() >= PRICE_SAMPLE_SIZE) {
                break;
            }
            goodsRepository.findById(id)
                    .filter(g -> g.getPrice() != null)
                    .ifPresent(g -> samples.put(g.getId(), g));
        }

        // 相似样本不够时退回同类目补齐，但要如实告诉用户这是"粗估"
        int exactCount = samples.size();
        boolean coarse = exactCount < MIN_PRICE_SAMPLES;
        if (coarse && category != null && !category.isBlank()) {
            for (Goods goods : goodsRepository.findAll()) {
                if (samples.size() >= PRICE_SAMPLE_SIZE) {
                    break;
                }
                if (category.equals(goods.getCategory()) && goods.getPrice() != null) {
                    samples.putIfAbsent(goods.getId(), goods);
                }
            }
        }

        if (samples.size() < MIN_PRICE_SAMPLES) {
            result.put("available", false);
            result.put("message", "同类商品太少（只有 " + samples.size() + " 件），暂时估不出参考价");
            return ResponseEntity.ok(result);
        }

        List<BigDecimal> prices = new ArrayList<>();
        for (Goods goods : samples.values()) {
            prices.add(goods.getPrice());
        }
        prices.sort(Comparator.naturalOrder());

        // 中位数：偶数个样本取中间两个的平均，避免"取偏大那个"把建议价抬高
        BigDecimal median;
        int size = prices.size();
        if (size % 2 == 1) {
            median = prices.get(size / 2);
        } else {
            median = prices.get(size / 2 - 1).add(prices.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP);
        }
        BigDecimal low = prices.get((int) Math.floor((size - 1) * 0.25));
        BigDecimal high = prices.get((int) Math.ceil((size - 1) * 0.75));

        List<Map<String, Object>> sampleViews = new ArrayList<>();
        for (Goods goods : samples.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", goods.getId());
            item.put("title", goods.getTitle());
            item.put("price", goods.getPrice());
            item.put("category", goods.getCategory());
            item.put("status", goods.getStatus());
            sampleViews.add(item);
        }

        result.put("available", true);
        result.put("suggested", median);
        result.put("low", low);
        result.put("high", high);
        result.put("sampleCount", samples.size());
        result.put("exactCount", exactCount);
        result.put("coarse", coarse);
        result.put("samples", sampleViews);
        String basis = coarse
                ? "同分类 " + samples.size() + " 件商品（只找到 " + exactCount + " 件高度相似，属于粗估）"
                : "同类 " + samples.size() + " 件商品";
        result.put("message", "参考" + basis + "，建议定价 ¥"
                + median.stripTrailingZeros().toPlainString());
        return ResponseEntity.ok(result);
    }

    /**
     * SSE 流式转发：不缓冲，把 AI 服务的流逐行透传给前端，
     * 保证「首字响应 ≤1 秒」在跨服务调用后依然成立。
     */
    @PostMapping("/chat/stream")
    public ResponseEntity<StreamingResponseBody> chatStream(
            @RequestBody(required = false) Map<String, Object> body) {
        StreamingResponseBody stream = out ->
                aiClient.streamChat(body == null ? Map.of() : body, out);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }

    // ---- 运营端代理（知识库管理与统计）----

    @GetMapping("/ops/kb/shards")
    public ResponseEntity<?> kbShards() {
        return wrapOrFallback(aiClient.json(HttpMethod.GET, "/ai/ops/kb/shards", null));
    }

    @GetMapping("/ops/kb/entries")
    public ResponseEntity<?> kbEntries(@RequestParam(required = false) String shard,
                                      @RequestParam(name = "q", required = false) String keyword) {
        StringBuilder path = new StringBuilder("/ai/ops/kb/entries");
        boolean first = true;
        if (shard != null && !shard.isBlank()) {
            path.append(first ? "?" : "&").append("shard=").append(shard);
            first = false;
        }
        if (keyword != null && !keyword.isBlank()) {
            path.append(first ? "?" : "&").append("q=").append(URLEncoder.encode(keyword, StandardCharsets.UTF_8));
        }
        return wrapOrFallback(aiClient.json(HttpMethod.GET, path.toString(), null));
    }

    @PostMapping("/ops/kb/entries")
    public ResponseEntity<?> kbSave(@RequestBody Map<String, Object> body) {
        return wrapOrFallback(aiClient.json(HttpMethod.POST, "/ai/ops/kb/entries", body));
    }

    @PostMapping("/ops/kb/reindex")
    public ResponseEntity<?> kbReindex() {
        return wrapOrFallback(aiClient.json(HttpMethod.POST, "/ai/ops/kb/reindex", Map.of()));
    }

    @DeleteMapping("/ops/kb/entries/{id}")
    public ResponseEntity<?> kbDelete(@PathVariable("id") String id) {
        return wrapOrFallback(aiClient.json(HttpMethod.DELETE, "/ai/ops/kb/entries/" + id, null));
    }

    @PostMapping("/ops/kb/import")
    public ResponseEntity<?> kbImport(@RequestBody Map<String, Object> body) {
        return wrapOrFallback(aiClient.json(HttpMethod.POST, "/ai/ops/kb/import", body));
    }

    /** 审计日志查询（运营端可看谁在什么时候改了哪条）。 */
    @GetMapping("/ops/kb/audit")
    public ResponseEntity<?> kbAudit(@RequestParam(defaultValue = "50") int limit) {
        return wrapOrFallback(aiClient.json(HttpMethod.GET,
                "/ai/ops/kb/audit?limit=" + limit, null));
    }

    /** 知识库 CSV 导出：透传 AI 服务原始字节，带 Content-Disposition 触发浏览器下载。 */
    @GetMapping("/ops/kb/entries.csv")
    public ResponseEntity<?> kbExport() {
        byte[] body = aiClient.bytes("/ai/ops/kb/entries.csv");
        if (body == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false, "available", false, "message", "AI 服务不可用"));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "knowledge.csv");
        return ResponseEntity.ok()
                .headers(headers)
                .body(new ByteArrayResource(body));
    }

    @GetMapping("/ops/stats/summary")
    public ResponseEntity<?> statsSummary() {
        return wrapOrFallback(aiClient.json(HttpMethod.GET, "/ai/ops/stats/summary", null));
    }

    @GetMapping("/ops/stats/top-questions")
    public ResponseEntity<?> statsTop(@RequestParam(defaultValue = "10") int limit) {
        return wrapOrFallback(aiClient.json(HttpMethod.GET,
                "/ai/ops/stats/top-questions?limit=" + limit, null));
    }

    @GetMapping("/ops/stats/misses")
    public ResponseEntity<?> statsMisses(@RequestParam(defaultValue = "50") int limit) {
        return wrapOrFallback(aiClient.json(HttpMethod.GET,
                "/ai/ops/stats/misses?limit=" + limit, null));
    }

    /** 拒答样本 CSV 导出：同上，原始字节透传。 */
    @GetMapping("/ops/stats/misses.csv")
    public ResponseEntity<?> statsMissesCsv() {
        byte[] body = aiClient.bytes("/ai/ops/stats/misses.csv");
        if (body == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false, "available", false, "message", "AI 服务不可用"));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "misses.csv");
        return ResponseEntity.ok()
                .headers(headers)
                .body(new ByteArrayResource(body));
    }

    private ResponseEntity<?> wrapOrFallback(JSONObject resp) {
        if (resp == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("available", false);
            error.put("message", "AI 服务不可用，请确认 8000 端口已启动");
            return ResponseEntity.ok(error);
        }
        return ResponseEntity.ok(resp);
    }

    /** 统一包装 AI 服务响应；服务不可用时给出 available=false，前端据此提示。 */
    private Map<String, Object> wrap(JSONObject resp) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        if (resp == null) {
            result.put("available", false);
            return result;
        }
        result.put("available", true);
        result.putAll(resp);
        return result;
    }

    /** 定价用的关键词切分：见 KeywordUtil（与求购撮合共用同一套切分规则）。 */

    private static String text(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return "";
        }
        return body.get(key).toString();
    }

    private static BigDecimal decimal(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        try {
            return new BigDecimal(body.get(key).toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
