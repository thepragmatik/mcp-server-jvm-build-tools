/*
 *
 *  Copyright 2025 Rahul Thakur
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.pragmatik.buildtools;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON utilities — no Jackson dependency required.
 * <p>
 * Produces valid JSON strings for Maps, Lists, and primitives commonly used
 * by BuildToolsService and DependencyService. All methods return compact,
 * single-line JSON suitable for MCP JSON-RPC responses.
 */
public final class JsonUtils {

    private JsonUtils() {
        // utility class
    }

    /**
     * Serialize a Map to a compact JSON string (single line).
     */
    public static String toJson(Map<String, Object> map) {
        if (map == null) return "{}";
        StringBuilder sb = new StringBuilder(1024);
        sb.append('{');
        var iter = map.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            sb.append('"').append(escapeJson(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
            if (iter.hasNext()) sb.append(',');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Serialize a List to a compact JSON array string.
     */
    public static String toJson(List<?> list) {
        if (list == null) return "[]";
        StringBuilder sb = new StringBuilder(512);
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            appendValue(sb, list.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Build a standard MCP error response.
     */
    public static String errorJson(String message) {
        return "{\"success\":false,\"error\":\"" + escapeJson(message) + "\"}";
    }

    /**
     * Build a standard MCP error response with tool name.
     */
    public static String errorJson(String message, String tool) {
        return "{\"success\":false,\"error\":\"" + escapeJson(message) + "\",\"tool\":\"" + escapeJson(tool) + "\"}";
    }

    // ─── internal helpers ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append(toJson((Map<String, Object>) value));
        } else if (value instanceof List) {
            sb.append(toJson((List<?>) value));
        } else {
            sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
        }
    }

    /**
     * Escape a string for use inside a JSON double-quoted string.
     * Handles: backslash, double-quote, newline, carriage-return, tab,
     * and control characters (\\uXXXX for characters below 0x20 not otherwise handled).
     */
    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
