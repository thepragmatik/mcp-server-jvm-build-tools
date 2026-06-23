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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Validates the standard MCP request headers ({@code Mcp-Method}, {@code Mcp-Name})
 * introduced by the 2026-07-28 RC (SEP-2243) against the JSON-RPC body of POST
 * requests to the Streamable HTTP transport ({@code POST /mcp/**}).
 *
 * <h2>HeaderMismatch behaviour</h2>
 * When a header is present but disagrees with the body, the request is rejected
 * with HTTP {@code 400} and a JSON-RPC {@code HeaderMismatchError} payload:
 * <ul>
 *   <li>{@code Mcp-Method} must equal the JSON-RPC {@code method} of the body.</li>
 *   <li>{@code Mcp-Name} must equal this server's configured name (identity).</li>
 * </ul>
 *
 * <h2>Backward compatibility (additive / opt-in)</h2>
 * The headers are <b>required</b> only by the 2026-07-28 RC. Older MCP clients
 * (2024-11-05, 2025-03-26) do not send them. To avoid breaking those clients this
 * filter is purely <b>additive</b>:
 * <ul>
 *   <li>If a header is <b>absent</b>, the request passes through unchanged.</li>
 *   <li>If a header is <b>present and matches</b>, the request passes through.</li>
 *   <li>Only a present-and-<b>contradictory</b> header is rejected.</li>
 *   <li>Unparseable / non-JSON bodies are <b>not</b> rejected here — they are left
 *       for the downstream transport to handle, so this filter never manufactures
 *       errors from parse failures.</li>
 * </ul>
 * It therefore never changes the behaviour seen by an existing, well-formed client;
 * it only catches genuinely self-contradictory requests.
 *
 * <p>The filter is dormant unless the HTTP transport is active (it only acts on
 * {@code POST /mcp/**}); in the default stdio mode there is no servlet container.
 */
@Component
@Order(2)
public class McpHeaderValidationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpHeaderValidationFilter.class);

    /** Path prefix of the MCP Streamable HTTP transport endpoints. */
    static final String MCP_PATH_PREFIX = "/mcp/";

    static final String HEADER_MCP_METHOD = "Mcp-Method";
    static final String HEADER_MCP_NAME = "Mcp-Name";

    /** JSON-RPC "Invalid Request" code, reused for the HeaderMismatch error. */
    static final int JSONRPC_INVALID_REQUEST = -32600;

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The server identity advertised to clients. {@code Mcp-Name}, when present,
     * must match this value. Resolved from the MCP server name (falling back to the
     * application name) so it stays consistent with the rest of the server card.
     */
    @Value("${spring.ai.mcp.server.name:${spring.application.name:mcp-server-jvm-build-tools}}")
    private String serverName = "mcp-server-jvm-build-tools";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpReq) || !(response instanceof HttpServletResponse httpRes)) {
            chain.doFilter(request, response);
            return;
        }

        if (!isMcpPost(httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        final String headerMethod = trimToNull(httpReq.getHeader(HEADER_MCP_METHOD));
        final String headerName = trimToNull(httpReq.getHeader(HEADER_MCP_NAME));

        // No standard MCP headers present => legacy/older client. Pass through.
        if (headerMethod == null && headerName == null) {
            chain.doFilter(request, response);
            return;
        }

        // Buffer the body so we can inspect it and still forward it downstream.
        final CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(httpReq);

        String bodyMethod = null;
        Object bodyId = null;
        boolean parsed = false;
        try {
            byte[] body = cached.getCachedBody();
            if (body.length > 0) {
                JsonNode root = objectMapper.readTree(body);
                if (root != null && root.isObject()) {
                    parsed = true;
                    JsonNode methodNode = root.get("method");
                    if (methodNode != null && methodNode.isTextual()) {
                        bodyMethod = methodNode.asText();
                    }
                    JsonNode idNode = root.get("id");
                    if (idNode != null && !idNode.isNull()) {
                        bodyId = idNode.isNumber() ? idNode.numberValue() : idNode.asText();
                    }
                }
            }
        } catch (IOException parseFailure) {
            // Malformed / non-JSON body: not our concern. Let the transport handle it.
            log.debug("Skipping MCP header validation for unparseable body: {}", parseFailure.getMessage());
            chain.doFilter(cached, response);
            return;
        }

        // Mcp-Method disagreement: only when we positively read a different method.
        if (parsed && headerMethod != null && bodyMethod != null && !headerMethod.equals(bodyMethod)) {
            rejectMismatch(
                    httpRes,
                    bodyId,
                    "Mcp-Method header '" + headerMethod + "' does not match JSON-RPC body method '" + bodyMethod
                            + "'");
            return;
        }

        // Mcp-Name disagreement: header must match this server's configured identity.
        final String configuredName = trimToNull(serverName);
        if (headerName != null && configuredName != null && !headerName.equals(configuredName)) {
            rejectMismatch(
                    httpRes,
                    bodyId,
                    "Mcp-Name header '" + headerName + "' does not match this server's name '" + configuredName + "'");
            return;
        }

        chain.doFilter(cached, response);
    }

    private boolean isMcpPost(HttpServletRequest req) {
        if (!"POST".equalsIgnoreCase(req.getMethod())) {
            return false;
        }
        String path = req.getServletPath();
        if (path == null || path.isEmpty()) {
            path = req.getRequestURI();
        }
        return path != null && path.startsWith(MCP_PATH_PREFIX);
    }

    private void rejectMismatch(HttpServletResponse response, Object id, String detail) throws IOException {
        log.debug("Rejecting MCP request (HeaderMismatch): {}", detail);
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(buildErrorBody(id, detail));
    }

    /**
     * Build a JSON-RPC error envelope for the HeaderMismatch. Visible for testing.
     */
    static String buildErrorBody(Object id, String detail) {
        String idJson;
        if (id == null) {
            idJson = "null";
        } else if (id instanceof Number) {
            idJson = id.toString();
        } else {
            idJson = "\"" + JsonUtils.escapeJson(id.toString()) + "\"";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":" + JSONRPC_INVALID_REQUEST
                + ",\"message\":\"HeaderMismatchError\",\"data\":{\"detail\":\"" + JsonUtils.escapeJson(detail)
                + "\"}}}";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Wraps a request so its body can be read for validation and then replayed to
     * the downstream transport. The body is buffered once in the constructor and
     * every {@link #getInputStream()} / {@link #getReader()} call serves a fresh
     * stream over that buffer.
     */
    static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        byte[] getCachedBody() {
            return cachedBody.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
        }
    }

    /** A replayable {@link ServletInputStream} backed by an in-memory byte array. */
    private static final class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] body) {
            this.buffer = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Async reads are not supported by the cached MCP body stream");
        }

        @Override
        public int read() {
            return buffer.read();
        }
    }
}
