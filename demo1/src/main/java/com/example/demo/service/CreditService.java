package com.example.demo.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.demo.repository.ReviewRepository;

/**
 * 信用分（B3）。不单独存表，按收到的评价实时聚合，避免与评价数据不一致。
 *
 * 公式：信用分 = 60（基准）+ (平均星级 - 3) × 15 + min(评价数, 10)，钳制在 0-100。
 * 含义：3 星是"不赚不亏"的中性线；满分 5 星且评价足够多时到 100；收到差评会明显掉分。
 * 没有任何评价时返回基准分 60，即"新用户基准分"。
 */
@Service
public class CreditService {

    public static final int BASE_SCORE = 60;
    private static final int STAR_WEIGHT = 15;
    private static final int MAX_VOLUME_BONUS = 10;

    @Autowired
    private ReviewRepository reviewRepository;

    public int scoreOf(Long userId) {
        if (userId == null) {
            return BASE_SCORE;
        }
        long count = reviewRepository.countByRevieweeId(userId);
        if (count == 0) {
            return BASE_SCORE;
        }
        Double avg = reviewRepository.avgScoreByRevieweeId(userId);
        double average = avg == null ? 3.0 : avg;

        double raw = BASE_SCORE + (average - 3) * STAR_WEIGHT + Math.min(count, MAX_VOLUME_BONUS);
        return (int) Math.max(0, Math.min(100, Math.round(raw)));
    }

    /** 供接口返回：分数 + 平均星级 + 评价条数 + 等级文案。 */
    public Map<String, Object> summaryOf(Long userId) {
        long count = reviewRepository.countByRevieweeId(userId);
        Double avg = count == 0 ? null : reviewRepository.avgScoreByRevieweeId(userId);
        double average = avg == null ? 0 : Math.round(avg * 10) / 10.0;
        int score = scoreOf(userId);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("creditScore", score);
        summary.put("avgScore", average);
        summary.put("reviewCount", count);
        summary.put("creditLevel", levelOf(score, count));
        return summary;
    }

    /** 等级文案：答辩时比裸数字好解释。 */
    private String levelOf(int score, long count) {
        if (count == 0) {
            return "新用户";
        }
        if (score >= 90) {
            return "信用优秀";
        }
        if (score >= 75) {
            return "信用良好";
        }
        if (score >= 60) {
            return "信用一般";
        }
        return "信用偏低";
    }
}
