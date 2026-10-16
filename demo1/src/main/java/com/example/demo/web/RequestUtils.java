package com.example.demo.web;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 请求体取值工具：把 {@code @RequestBody Map<String,Object>} 里的字段
 * 安全地转成 String / Long / Integer / BigDecimal。
 *
 * <p>分散在各 Controller 的私有 {@code text / longOf / intOf / decimalOf}
 * 逻辑完全一致：null 安全、trim、空串当作空。抽到一处避免改一个地方后
 * 其它 Controller 默默走不同的逻辑。</p>
 */
public final class RequestUtils {

    private RequestUtils() {
    }

    /** 取字段文本：null 视为空串，自动 trim。 */
    public static String textOf(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : value.toString().trim();
    }

    /** 取 Long：空串 / 非数字返回 null（不抛错，便于做参数缺失校验）。 */
    public static Long longOf(Map<String, Object> body, String key) {
        String value = textOf(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 Integer：空串 / 非数字返回 null。 */
    public static Integer intOf(Map<String, Object> body, String key) {
        String value = textOf(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 BigDecimal：空串 / 非数字返回 null。 */
    public static BigDecimal decimalOf(Map<String, Object> body, String key) {
        String value = textOf(body, key);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
