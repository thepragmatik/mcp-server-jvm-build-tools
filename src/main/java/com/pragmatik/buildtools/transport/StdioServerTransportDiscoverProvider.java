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

import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Decorator over the stdio {@link McpServerTransportProvider} that makes the stdio
 * session answer the {@code server/discover} JSON-RPC method (2026-07-28 RC,
 * SEP-2575) — the RC's <i>backward-compatibility probe on stdio</i> — for stdio-only
 * deployments where the HTTP discover controllers are inactive
 * ({@code spring.main.web-application-type=none}).
 * <p>
 * {@code McpServer.sync(provider)} hands its session factory to the provider via
 * {@link #setSessionFactory(McpServerSession.Factory)}; this decorator composes that
 * factory so the sessions it creates are {@link StdioDiscoverSession}s — answering
 * {@code server/discover} from the shared {@code McpDiscoverController#discoverResult()}
 * source and delegating all other traffic to the framework session. Every other
 * provider method forwards to the delegate, so stdio behaviour is unchanged apart from
 * the new method.
 */
public class StdioServerTransportDiscoverProvider implements McpServerTransportProvider {

    private final McpServerTransportProvider delegate;

    private final FactoryDecorator factoryDecorator;

    /**
     * Composes the wrapped {@code McpServerSession.Factory}.
     */
    @FunctionalInterface
    public interface FactoryDecorator {

        /**
         * Wraps a framework session factory so the sessions it creates answer
         * {@code server/discover}.
         *
         * @param delegate the framework-built factory
         * @return the decorating factory
         */
        McpServerSession.Factory decorate(McpServerSession.Factory delegate);
    }

    /**
     * Creates the decorating provider.
     *
     * @param delegate the stdio transport provider created by
     *        {@code McpServerTransportConfiguration} (owns the stdio streams)
     * @param factoryDecorator wraps the framework's {@code McpServerSession.Factory} so
     *        created sessions answer {@code server/discover}
     */
    public StdioServerTransportDiscoverProvider(
            McpServerTransportProvider delegate, FactoryDecorator factoryDecorator) {
        this.delegate = delegate;
        this.factoryDecorator = factoryDecorator;
    }

    /**
     * Wraps the framework's session factory so created sessions answer
     * {@code server/discover}, then hands it to the delegate provider (which owns the
     * stdio streams and session lifecycle).
     */
    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(factoryDecorator.decorate(sessionFactory));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public List<String> protocolVersions() {
        return delegate.protocolVersions();
    }
}
