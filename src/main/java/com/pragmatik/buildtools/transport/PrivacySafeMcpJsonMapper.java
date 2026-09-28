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
package com.pragmatik.buildtools.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared stdio/HTTP serialization boundary for framework-generated JSON-RPC errors.
 * The SDK includes unknown caller-supplied identifiers in some error messages. Those
 * messages must never become model-visible output, so only the error code survives.
 * Successful results and already-projected tool diagnostics are left untouched.
 */
public final class PrivacySafeMcpJsonMapper implements McpJsonMapper {

    private static final Logger log = LoggerFactory.getLogger(PrivacySafeMcpJsonMapper.class);

    private final McpJsonMapper delegate;

    public PrivacySafeMcpJsonMapper(McpJsonMapper delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(safe(value));
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(safe(value));
    }

    private static Object safe(Object value) {
        if (value instanceof JSONRPCResponse response) {
            if (response.error() != null) {
                Integer code = response.error().code();
                if (Integer.valueOf(-32603).equals(code)) {
                    log.error("MCP SDK returned an internal error; wire details withheld");
                }
                return JSONRPCResponse.error(response.id(), new JSONRPCError(code, safeMessage(code)));
            }
            if (response.result() instanceof Throwable) {
                log.error("MCP SDK returned a throwable result; wire details withheld");
                return internalError(response.id());
            }
        }
        if (value instanceof Throwable) {
            log.error("MCP SDK attempted to serialize a throwable; wire details withheld");
            return internalError(null);
        }
        return value;
    }

    private static Object internalError(Object id) {
        if (id == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("jsonrpc", "2.0");
            error.put("id", null);
            error.put("error", Map.of("code", -32603, "message", "Internal error"));
            return error;
        }
        return JSONRPCResponse.error(id, new JSONRPCError(-32603, "Internal error"));
    }

    private static String safeMessage(Integer code) {
        if (code == null) {
            return "Request failed";
        }
        return switch (code) {
            case -32700 -> "Parse error";
            case -32600 -> "Invalid Request";
            case -32601 -> "Method not found";
            case -32602 -> "Invalid params";
            case -32603 -> "Internal error";
            default -> "Request failed";
        };
    }
}
