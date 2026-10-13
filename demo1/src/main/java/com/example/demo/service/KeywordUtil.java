package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文关键词匹配的公共工具。
 *
 * 项目里有多处需要"不依赖分词器的轻量切分"：求购撮合、定价样本补扫，
 * 都是把中文串按 2 字滑窗切开放进字符串包含判断里。抽到一处，避免各写一份后走样。
 */
public final class KeywordUtil {

    private KeywordUtil() {
    }

    /** 去掉标点与空白后做 2 字滑窗，最多返回 limit 个片段。 */
    public static List<String> grams(String text, int limit) {
        List<String> grams = new ArrayList<>();
        if (text == null) {
            return grams;
        }
        String cleaned = text.replaceAll("[\\s,，。！!？?、；;：:\"'（）()【】\\[\\]]", "");
        for (int i = 0; i + 2 <= cleaned.length() && grams.size() < limit; i++) {
            grams.add(cleaned.substring(i, i + 2));
        }
        return grams;
    }
}
