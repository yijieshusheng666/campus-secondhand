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
    /** 相对分数下限：候选得分低于「最高分 × 该系数」的直接丢掉，砍掉长尾噪声 */
    private static final double RELATIVE_FLOOR = 0.35;
    /** 商品发布后最多提示几条命中的求购 */
    private static final int WANTED_HINT_LIMIT = 5;
    /** 2 字滑窗最多切多少片，防止长文本产生过多无意义片段 */
    private static final int GRAM_LIMIT = 40;

    @Autowired
    private AiClient aiClient;

    @Autowired
    private GoodsRepository goodsRepository;

    /** 需求 → 在售商品 */
    public List<Goods> matchGoods(Wanted wanted, int limit) {
        int cap = limit <= 0 ? MATCH_LIMIT : limit;
        String query = join(wanted.getTitle(), wanted.getCategory(), wanted.getDescription());

        // 求购是"发现"场景：不设绝对相似度阈值，而是取相对分靠前的候选。
        // 需求写得越具体、查询越长，词袋模式的余弦被稀释得越厉害：
        // 实测「求一副降噪耳机，预算两千以内」中 AirPods Pro 2 只有 0.1361，
        // 低于默认阈值 0.20 —— 按绝对阈值过滤的话，这件明明匹配、也在预算内的
        // 商品会被直接切掉，用户却看到"暂时没有匹配"。
        // 改用"相对最高分的一定比例"，跨模式都成立。
        List<Map<String, Object>> hits = aiClient.search(query, cap * OVER_FETCH, 0.0);
        double best = 0;
        for (Map<String, Object> hit : hits) {
            Object score = hit.get("score");
            if (score instanceof Number number) {
                best = Math.max(best, number.doubleValue());
            }
        }
        double floor = best * RELATIVE_FLOOR;

        List<Long> candidateIds = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Object score = hit.get("score");
            double value = score instanceof Number number ? number.doubleValue() : 0;
            if (value >= floor && hit.get("goodsId") instanceof Number id) {
                candidateIds.add(id.longValue());
            }
        }
        boolean semantic = !candidateIds.isEmpty();
        if (!semantic) {
            candidateIds = keywordCandidates(query, cap * OVER_FETCH);
        }

        List<Goods> matched = new ArrayList<>();
        for (Long id : candidateIds) {
            if (matched.size() >= cap) {
                break;
            }
            Goods goods = goodsRepository.findById(id).orElse(null);
            if (goods == null || !passes(wanted, goods)) {
                continue;
            }
            matched.add(goods);
        }
        return matched;
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

    /** 关键词兜底：沿用项目里原有的 2 字滑窗打分思路 */
    private List<Long> keywordCandidates(String query, int limit) {
        List<String> grams = KeywordUtil.grams(query, GRAM_LIMIT);
        Map<Long, Integer> scores = new LinkedHashMap<>();
        for (Goods goods : goodsRepository.findAll()) {
            if ("已售".equals(goods.getStatus())) {
                continue;
            }
            String text = join(goods.getTitle(), goods.getCategory(), goods.getDescription());
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
        List<Map.Entry<Long, Integer>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> b.getValue() - a.getValue());

        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : ranked) {
            if (ids.size() >= limit) {
                break;
            }
            ids.add(entry.getKey());
        }
        return ids;
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
