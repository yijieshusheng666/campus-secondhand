package com.example.demo.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一响应构造：所有 Controller 都用同一形状的 {@code {success, message, ...}}。
 *
 * <p>之前每个 Controller 都有自己手写的 {@code ok / fail / verifyOk / verifyFail}，
 * 字段顺序还各有差异（部分用 {@link LinkedHashMap}、部分用 {@code Map.of}）。
 * 抽到一处之后改响应格式只改这一处，前端解析逻辑也能跟着收敛。</p>
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    /** 成功：只带 message。 */
    public static Map<String, Object> ok(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
        return body;
    }

    /** 成功：message + 任意附加字段（按插入顺序保留）。 */
    public static Map<String, Object> ok(String message, Map<String, Object> extras) {
        Map<String, Object> body = ok(message);
        if (extras != null) {
            body.putAll(extras);
        }
        return body;
    }

    /** 失败：仅 message。 */
    public static Map<String, Object> fail(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);
        return body;
    }
}
