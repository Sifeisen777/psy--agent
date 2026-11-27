package com.psyagent.bootstrap.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参数读取与审计脱敏。
 * <p>
 * MCP 客户端传来的 JSON 参数类型是自由的（数字可能是 Integer/Double/String），
 * 集中做宽松转换，避免每个工具重复写 instanceof 判断。
 */
public final class ToolArgs {

    private static final List<String> SENSITIVE_KEYS = List.of(
            "contact", "phone", "email", "token", "password", "idcard"
    );

    private ToolArgs() {
    }

    public static String str(Map<String, Object> args, String key, String defaultValue) {
        Object value = args == null ? null : args.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    public static int intVal(Map<String, Object> args, String key, int defaultValue) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 读取整数数组。支持 JSON 数组与 "0,1,2" 形式的逗号分隔字符串，
     * 因为模型客户端两种都会给。
     */
    public static List<Integer> intList(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Number number) {
                    result.add(number.intValue());
                } else if (item != null) {
                    try {
                        result.add(Integer.parseInt(String.valueOf(item).trim()));
                    } catch (NumberFormatException ignored) {
                        // 单个非法值直接忽略，由工具按长度校验统一拒绝
                    }
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            for (String part : text.split("[,，\\s]+")) {
                try {
                    result.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // 同上
                }
            }
        }
        return result;
    }

    /** 审计日志用的副本：敏感字段只留尾部，避免联系方式等 PII 落进可导出的观测数据。 */
    public static Map<String, Object> masked(Map<String, Object> args) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (args == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value != null && SENSITIVE_KEYS.stream().anyMatch(k -> key.toLowerCase().contains(k))) {
                String text = String.valueOf(value);
                copy.put(key, text.length() <= 4 ? "****" : "****" + text.substring(text.length() - 4));
            } else {
                copy.put(key, value);
            }
        }
        return copy;
    }
}
