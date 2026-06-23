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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
 * The server identity used for the {@code Mcp-Name} check is resolved from the
 * shared {@link McpServerIdentity}, which is the same single source the server card
 * ({@code /.well-known/mcp-server}) and {@code server/discover} advertise. A client
 * that reads the card's {@code name} and echoes it as {@code Mcp-Name} therefore
 * always matches — the three surfaces cannot drift.
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
 * <h2>Bounded buffering</h2>
 * To inspect the body the filter buffers it, but only up to a configurable cap
 * ({@code mcp.transport.max-validation-body-bytes}, default 1 MiB). A request that
 * exceeds the cap is rejected with HTTP {@code 413} before the whole body is
 * materialised, so the opt-in HTTP transport has no unbounded memory-amplification
 * surface. The buffered bytes are parsed in place (no defensive array copy).
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

    /** Default body-buffer cap for validation: 1 MiB. */
    static final int DEFAULT_MAX_VALIDATION_BODY_BYTES = 1_048_576;

    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The shared server identity. {@code Mcp-Name}, when present, must equal
     * {@link McpServerIdentity#name()} — the same value the server card and
     * {@code server/discover} publish.
     */
    private final McpServerIdentity identity;

    /**
     * Maximum number of body bytes buffered for validation. A larger body is rejected
     * with HTTP {@code 413} before being fully read, capping memory use.
     */
    private final int maxValidationBodyBytes;

    public McpHeaderValidationFilter(
            McpServerIdentity identity,
            @Value("${mcp.transport.max-validation-body-bytes:1048576}") int maxValidationBodyBytes) {
        this.identity = identity;
        this.maxValidationBodyBytes =
                maxValidationBodyBytes > 0 ? maxValidationBodyBytes : DEFAULT_MAX_VALIDATION_BODY_BYTES;
    }

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

        // Buffer the body (bounded) so we can inspect it and still forward it downstream.
        final CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(httpReq, maxValidationBodyBytes);

        // Reject oversized bodies before materialising the whole payload (DoS guard).
        if (cached.exceedsLimit()) {
            rejectTooLarge(httpRes);
            return;
        }

        String bodyMethod = null;
        Object bodyId = null;
        boolean parsed = false;
        try {
            if (!cached.isBodyEmpty()) {
                // Parse the buffered bytes in place (no defensive copy).
                JsonNode root = objectMapper.readTree(cached.getInputStream());
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
        final String configuredName = trimToNull(identity.name());
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

    private void rejectTooLarge(HttpServletResponse response) throws IOException {
        log.debug("Rejecting MCP request: body exceeds {}-byte validation cap", maxValidationBodyBytes);
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write(buildJsonRpcError(
                        null,
                        "PayloadTooLargeError",
                        "Request body exceeds the " + maxValidationBodyBytes
                                + "-byte limit for MCP header validation"));
    }

    /**
     * Build a JSON-RPC error envelope for the HeaderMismatch. Visible for testing.
     */
    static String buildErrorBody(Object id, String detail) {
        return buildJsonRpcError(id, "HeaderMismatchError", detail);
    }

    /**
     * Build a JSON-RPC error envelope with the given {@code message} and {@code detail}.
     * The {@code id} is rendered as a number, a quoted/escaped string, or {@code null}.
     */
    private static String buildJsonRpcError(Object id, String message, String detail) {
        String idJson;
        if (id == null) {
            idJson = "null";
        } else if (id instanceof Number) {
            idJson = id.toString();
        } else {
            idJson = "\"" + JsonUtils.escapeJson(id.toString()) + "\"";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"error\":{\"code\":" + JSONRPC_INVALID_REQUEST
                + ",\"message\":\"" + JsonUtils.escapeJson(message) + "\",\"data\":{\"detail\":\""
                + JsonUtils.escapeJson(detail) + "\"}}}";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Wraps a request so its body can be read for validation and then replayed to the
     * downstream transport. The body is buffered once in the constructor — bounded by a
     * byte cap — and every {@link #getInputStream()} / {@link #getReader()} call serves a
     * fresh stream over that buffer. If the body exceeds the cap, reading stops early and
     * {@link #exceedsLimit()} reports {@code true}; callers must reject such a request
     * (the buffered prefix must not be forwarded as if it were a complete body).
     */
    static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;
        private final boolean exceedsLimit;

        CachedBodyHttpServletRequest(HttpServletRequest request, int maxBytes) throws IOException {
            super(request);
            int cap = maxBytes > 0 ? maxBytes : DEFAULT_MAX_VALIDATION_BODY_BYTES;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            boolean over = false;
            InputStream in = request.getInputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                int remaining = cap - buffer.size();
                if (read > remaining) {
                    if (remaining > 0) {
                        buffer.write(chunk, 0, remaining);
                    }
                    over = true;
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            this.cachedBody = buffer.toByteArray();
            this.exceedsLimit = over;
        }

        /** Whether the request body exceeded the validation byte cap. */
        boolean exceedsLimit() {
            return exceedsLimit;
        }

        /** Whether the buffered body is empty. */
        boolean isBodyEmpty() {
            return cachedBody.length == 0;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
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
