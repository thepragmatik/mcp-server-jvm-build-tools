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

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import java.util.Map;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/**
 * A stdio MCP server session that answers the {@code server/discover} JSON-RPC method
 * (2026-07-28 RC, SEP-2575) — the RC's <i>backward-compatibility probe on stdio</i>.
 * <p>
 * The bundled MCP SDK 2.0.0 does not model {@code server/discover} as a first-class
 * method: {@link McpServerSession} resolves request handlers from a private map with no
 * public registration API, so a protocol-speaking stdio client would receive
 * {@code Method not found} ({@code -32601}) for the probe. This session answers
 * {@code server/discover} directly — writing the JSON-RPC response through the same
 * stdio session transport the framework session uses — and delegates <i>every other
 * message</i> (requests, responses, notifications) to the framework-built session
 * untouched, so {@code initialize}, {@code tools/list}, {@code tools/call} and
 * notifications behave exactly as before.
 * <p>
 * The result object is supplied by {@link McpDiscoverController#discoverResult()} — the
 * same builder the HTTP surfaces ({@code /mcp/discover}, {@code POST /mcp}) use, driven
 * by the shared {@code McpServerIdentity} bean — so the stdio payload cannot drift from
 * the HTTP discover result. Only the response <i>envelope</i> differs by nature of the
 * transport: JSON-RPC over newline-delimited stdio instead of HTTP.
 * <p>
 * Because the probe is answered here rather than through the SDK's handler map, it
 * works even <b>before</b> the {@code initialize} handshake — the up-front
 * version-selection semantics the RC prescribes for the backward-compatibility probe.
 */
public class StdioDiscoverSession extends McpServerSession {

    /** The JSON-RPC method this session answers (2026-07-28 RC, SEP-2575). */
    static final String METHOD_SERVER_DISCOVER = "server/discover";

    /** JSON-RPC "Internal error" code, used when building the discover result fails. */
    static final int JSONRPC_INTERNAL_ERROR = -32603;

    private final McpServerSession delegate;

    private final McpServerTransport sessionTransport;

    private final Supplier<Map<String, Object>> discoverResultSupplier;

    /**
     * Creates a stdio session that intercepts {@code server/discover} ahead of the
     * framework session's handler map.
     *
     * @param sessionTransport the 1:1 stdio session transport responses are written to
     *        (the same instance the delegate session was created with)
     * @param delegate the framework-built stdio session every non-discover message is
     *        delegated to
     * @param discoverResultSupplier supplies the discover result (the shared HTTP
     *        source, {@code McpDiscoverController#discoverResult()})
     */
    StdioDiscoverSession(
            McpServerTransport sessionTransport,
            McpServerSession delegate,
            Supplier<Map<String, Object>> discoverResultSupplier) {
        super(delegate.getId(), null, sessionTransport, null, null, null);
        this.delegate = delegate;
        this.sessionTransport = sessionTransport;
        this.discoverResultSupplier = discoverResultSupplier;
    }

    /**
     * Answers {@code server/discover} directly; delegates every other message to the
     * framework session.
     */
    @Override
    public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
        if (message instanceof McpSchema.JSONRPCRequest request && METHOD_SERVER_DISCOVER.equals(request.method())) {
            return handleDiscover(request);
        }
        return delegate.handle(message);
    }

    private Mono<Void> handleDiscover(McpSchema.JSONRPCRequest request) {
        McpSchema.JSONRPCResponse response;
        try {
            response = McpSchema.JSONRPCResponse.result(request.id(), discoverResultSupplier.get());
        } catch (RuntimeException e) {
            // Fail closed with a standard JSON-RPC error envelope rather than crashing
            // the session; the client still receives a well-formed response.
            response = McpSchema.JSONRPCResponse.error(
                    request.id(),
                    new McpSchema.JSONRPCResponse.JSONRPCError(
                            JSONRPC_INTERNAL_ERROR, "server/discover failed: " + e.getMessage(), null));
        }
        return sessionTransport.sendMessage(response);
    }

    // ── Delegating session methods ───────────────────────────────────────────────
    // The superclass keeps its communication state in private fields initialised from
    // the constructor arguments (null here), so every McpSession/McpLoggableSession
    // method must forward to the delegate to keep the framework session's behaviour
    // identical for non-discover traffic.

    @Override
    public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
        return delegate.sendRequest(method, requestParams, typeRef);
    }

    @Override
    public Mono<Void> sendNotification(String method, Object params) {
        return delegate.sendNotification(method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
        delegate.setMinLoggingLevel(minLoggingLevel);
    }

    @Override
    public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel loggingLevel) {
        return delegate.isNotificationForLevelAllowed(loggingLevel);
    }
}
