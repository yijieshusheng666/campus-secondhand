package com.example.demo.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.demo.entity.Goods;
import com.example.demo.entity.Wanted;
import com.example.demo.repository.GoodsRepository;

/**
 * 求购反向撮合（C1）。
 *
 * 两个方向分别用不同策略，理由不同：
 * - **需求 → 商品**（求购详情页、求购广场）：走语义检索。买家写的是自然语言
 *   （「想找能降噪的耳机」），语义召回才能匹配到「索尼 WH-1000XM5 头戴式耳机」；
 *   AI 服务不可用时退回关键词匹配，保证功能永远可用。
 * - **商品 → 求购**（发布商品后的即时撮合）：走规则匹配。要对每条求购都算一次相似度，
 *   数据量小但调用次数多，用分类 + 预算 + 关键词的规则更快、也不依赖 AI 服务。
 */
@Service
public class WantedMatchService {

    /** 单次最多返回多少件匹配商品 */
    private static final int MATCH_LIMIT = 6;
    /** AI 召回超取倍数：Java 侧还要按分类/预算/在售过滤，避免过滤后不够 */
    private static final int OVER_FETCH = 3;
    /** 相对分数下限：候选得分低于「最高分 × 该系数」的需要字面证据支持 */
    private static final double RELATIVE_FLOOR = 0.35;
    /** 语义分达到「最高分 × 该系数」即可入选，不再要求字面证据 */
    private static final double STRONG_FLOOR = 0.6;
    /** 完全没有语义结果时，纯关键词兜底需要的最少命中数 */
    private static final int KEYWORD_ONLY_MIN = 3;
    /** 商品发布后最多提示几条命中的求购 */
    private static final int WANTED_HINT_LIMIT = 5;
    /** 2 字滑窗最多切多少片，防止长文本产生过多无意义片段 */
    private static final int GRAM_LIMIT = 40;

    @Autowired
    private AiClient aiClient;

    @Autowired
    private GoodsRepository goodsRepository;

    /**
     * 需求 → 在售商品。
     *
     * 返回结果里带上「凭什么匹配」的说明，前端直接展示 —— 匹配是不是真实发生，
     * 用户自己就能核对，不用信我们一句话。
     */
    public MatchResult matchGoods(Wanted wanted, int limit) {
        int cap = limit <= 0 ? MATCH_LIMIT : limit;
        // 检索词只用"标题 + 补充说明"，**不要把分类拼进去**：
        // 分类名出现在同类目每件商品的文案里，拼进去会让整个类目互相"相似"
        //（实测「想找一副能降噪的耳机 电子产品」把 Apple Watch、小米 14 都拉进结果，
        //  它们的真实相关度是 0）。分类只用于过滤和理由说明。
        String query = join(wanted.getTitle(), wanted.getDescription());

        // 参与比对的商品池 = 站上全部商品（含已售；已售的价格/标题同样是"像不像"的依据），
        // 结果里再按"在售 + 分类 + 预算"过滤掉不能买的。
        List<Goods> all = goodsRepository.findAll();
        int scanned = all.size();

        // 求购是"发现"场景：不设绝对相似度阈值，而是取相对分靠前的候选。
        // 需求写得越具体、查询越长，词袋模式的余弦被稀释得越厉害：
        // 实测「求一副降噪耳机，预算两千以内」中 AirPods Pro 2 只有 0.1361，
        // 低于默认阈值 0.20 —— 按绝对阈值过滤的话，这件明明匹配、也在预算内的
        // 商品会被直接切掉，用户却看到"暂时没有匹配"。
        // 改用"相对最高分的一定比例"，跨模式都成立。
        List<Map<String, Object>> hits = aiClient.search(query, cap * OVER_FETCH, 0.0);
        double best = 0;
        for (Map<String, Object> hit : hits) {
            if (hit.get("score") instanceof Number number) {
                best = Math.max(best, number.doubleValue());
            }
        }
        double floor = best * RELATIVE_FLOOR;

        Map<Long, Double> semanticScores = new LinkedHashMap<>();
        for (Map<String, Object> hit : hits) {
            Object score = hit.get("score");
            double value = score instanceof Number number ? number.doubleValue() : 0;
            if (value > 0 && hit.get("goodsId") instanceof Number id) {
                semanticScores.put(id.longValue(), value);
            }
        }

        // 关键词扫描兜底：覆盖 AI 索引之外的商品（索引只含在售，且 AI 服务可能没启动）
        Map<Long, Integer> keywordScores = keywordScores(query);

        // 合并候选并按分数排序：语义分与关键词命中数归一到同一量纲比较
        Map<Long, Double> merged = new LinkedHashMap<>();
        semanticScores.forEach((id, score) -> merged.merge(id, score, Math::max));
        keywordScores.forEach((id, count) -> {
            double normalized = Math.min(count / 3.0, 1.0);   // 命中 3 处以上算强相关
            merged.merge(id, normalized, Math::max);
        });

        List<Long> ranked = new ArrayList<>(merged.keySet());
        ranked.sort((a, b) -> Double.compare(merged.get(b), merged.get(a)));

        List<Goods> matched = new ArrayList<>();
        Map<Long, String> reasons = new LinkedHashMap<>();
        for (Long id : ranked) {
            if (matched.size() >= cap) {
                break;
            }
            double semantic = semanticScores.getOrDefault(id, 0.0);
            int keyword = keywordScores.getOrDefault(id, 0);
            if (!qualifies(semantic, keyword, best, floor)) {
                continue;
            }
            Goods goods = goodsRepository.findById(id).orElse(null);
            if (goods == null || !passes(wanted, goods)) {
                continue;
            }
            matched.add(goods);
            reasons.put(goods.getId(), reasonOf(wanted, goods, semantic, keyword));
        }
        return new MatchResult(matched, reasons, scanned, semanticScores.size());
    }

    /**
     * 入选门槛：宁可少给几条，也别把无关商品塞进去。
     *
     * - 语义分接近最高分（≥ 0.6 倍）→ 认；
     * - 语义分达标（≥ 0.35 倍）**且** 字面也有关键词命中 → 认（语义 + 字面双证据）；
     * - 完全没有语义结果（AI 服务没启动）时，纯靠关键词命中 ≥ 3 处兜底。
     *
     * 为什么需要第二、三条：只按"相对最高分"过滤时，如果最高分本身就很低
     *（长查询被稀释），下限会低到不起作用 —— 实测「想找一副能降噪的耳机」
     * 最高分 0.24，0.35 倍只有 0.084，于是 0.09 的 Apple Watch、小米 14 全都过了。
     */
    private boolean qualifies(double semantic, int keyword, double best, double floor) {
        if (semantic >= best * STRONG_FLOOR) {
            return true;
        }
        if (semantic >= floor && keyword >= 1) {
            return true;
        }
        return semantic == 0 && keyword >= KEYWORD_ONLY_MIN;
    }

    /** 说明这条匹配是怎么来的：相似度 + 通过了哪些校验。 */
    private String reasonOf(Wanted wanted, Goods goods, double semantic, int keywordHits) {
        List<String> parts = new ArrayList<>();
        if (semantic > 0) {
            parts.add(String.format("语义相似 %.2f", semantic));
        }
        if (keywordHits > 0) {
            parts.add("关键词命中 " + keywordHits + " 处");
        }
        if (semantic == 0 && keywordHits == 0) {
            parts.add("同分类推荐");
        }
        if (notBlank(wanted.getCategory()) && wanted.getCategory().equals(goods.getCategory())) {
            parts.add("分类一致");
        }
        if (wanted.getMaxPrice() != null && goods.getPrice() != null
                && goods.getPrice().compareTo(wanted.getMaxPrice()) <= 0) {
            parts.add("在预算内（¥" + goods.getPrice().stripTrailingZeros().toPlainString()
                    + " ≤ ¥" + wanted.getMaxPrice().stripTrailingZeros().toPlainString() + "）");
        }
        parts.add("在售可买");
        return String.join(" ｜ ", parts);
    }

    /** 匹配结果 + 可核对的依据 */
    public static class MatchResult {
        private final List<Goods> goods;
        private final Map<Long, String> reasons;
        private final int scanned;
        private final int semanticCount;

        MatchResult(List<Goods> goods, Map<Long, String> reasons, int scanned, int semanticCount) {
            this.goods = goods;
            this.reasons = reasons;
            this.scanned = scanned;
            this.semanticCount = semanticCount;
        }

        public List<Goods> getGoods() {
            return goods;
        }

        public Map<Long, String> getReasons() {
            return reasons;
        }

        /** 参与比对的商品总数（站上全部商品） */
        public int getScanned() {
            return scanned;
        }

        /** 语义召回命中的候选数，0 表示这次是纯关键词兜底 */
        public int getSemanticCount() {
            return semanticCount;
        }

        public List<Map<String, Object>> getMatchInfo() {
            List<Map<String, Object>> info = new ArrayList<>();
            for (Goods item : goods) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("goodsId", item.getId());
                one.put("reason", reasons.get(item.getId()));
                info.add(one);
            }
            return info;
        }
    }

    /** 这件商品命中了哪些进行中的求购（发布商品后即时提示用） */
    public List<Wanted> matchWanted(Goods goods, List<Wanted> openWanteds) {
        String goodsText = join(goods.getTitle(), goods.getCategory(), goods.getDescription());
        Map<Wanted, Integer> scored = new LinkedHashMap<>();

        for (Wanted wanted : openWanteds) {
            int score = 0;
            if (notBlank(wanted.getCategory()) && wanted.getCategory().equals(goods.getCategory())) {
                score += 2;   // 分类一致
            }
            if (wanted.getMaxPrice() != null && goods.getPrice() != null
                    && goods.getPrice().compareTo(wanted.getMaxPrice()) <= 0) {
                score += 1;   // 在预算内
            }
            for (String gram : KeywordUtil.grams(wanted.getTitle(), GRAM_LIMIT)) {
                if (goodsText.contains(gram)) {
                    score += 2;   // 标题关键词命中
                }
            }
            if (score > 0) {
                scored.put(wanted, score);
            }
        }

        List<Map.Entry<Wanted, Integer>> ranked = new ArrayList<>(scored.entrySet());
        ranked.sort((a, b) -> b.getValue() - a.getValue());

        List<Wanted> result = new ArrayList<>();
        for (Map.Entry<Wanted, Integer> entry : ranked) {
            if (result.size() >= WANTED_HINT_LIMIT) {
                break;
            }
            result.add(entry.getKey());
        }
        return result;
    }

    // ---- 内部工具 ----

    private boolean passes(Wanted wanted, Goods goods) {
        if ("已售".equals(goods.getStatus())) {
            return false;
        }
        if (notBlank(wanted.getCategory()) && !"不限".equals(wanted.getCategory())
                && !wanted.getCategory().equals(goods.getCategory())) {
            return false;
        }
        if (wanted.getMaxPrice() != null && goods.getPrice() != null
                && goods.getPrice().compareTo(wanted.getMaxPrice()) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 关键词扫描：对**站上全部商品**（含已售）做 2 字滑窗命中计数。
     * 与语义召回互补 —— 语义只覆盖 AI 索引里的在售商品，这一层保证不漏。
     */
    private Map<Long, Integer> keywordScores(String query) {
        List<String> grams = KeywordUtil.grams(query, GRAM_LIMIT);
        Map<Long, Integer> scores = new LinkedHashMap<>();
        if (grams.isEmpty()) {
            return scores;
        }
        for (Goods goods : goodsRepository.findAll()) {
            String text = join(goods.getTitle(), goods.getCategory(), goods.getDescription(),
                    goods.getCourseCode());
            int score = 0;
            for (String gram : grams) {
                if (text.contains(gram)) {
                    score++;
                }
            }
            if (score > 0) {
                scores.put(goods.getId(), score);
            }
        }
        return scores;
    }

    /** 2 字滑窗切分见 KeywordUtil：与定价样本补扫共用同一套规则 */
    private String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (notBlank(part)) {
                builder.append(part.trim()).append(' ');
            }
        }
        return builder.toString().trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
