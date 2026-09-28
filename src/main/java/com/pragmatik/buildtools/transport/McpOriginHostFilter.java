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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;

/** Validates MCP HTTP Host and Origin before authorization or SDK dispatch. */
@Component
@Profile("http")
@Order(0)
public final class McpOriginHostFilter implements Filter {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final boolean loopbackBind;
    private final Set<String> additionalHosts;
    private final CorsConfiguration cors;

    public McpOriginHostFilter(
            TransportConfig transportConfig,
            @Value("${server.address:127.0.0.1}") String bindAddress,
            @Value("${mcp.transport.allowed-hosts:}") String allowedHosts) {
        this.loopbackBind = TransportConfig.isLoopback(bindAddress);
        this.additionalHosts = Arrays.stream(allowedHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(McpOriginHostFilter::configuredHost)
                .collect(Collectors.toUnmodifiableSet());
        this.cors = new CorsConfiguration();
        String[] configured = transportConfig.parsedAllowedOrigins();
        if (transportConfig.usesWildcard()) {
            cors.setAllowedOriginPatterns(Arrays.asList(configured));
        } else {
            cors.setAllowedOrigins(Arrays.asList(configured));
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)
                || !(response instanceof HttpServletResponse httpRes)
                || !isMcpPath(httpReq)) {
            chain.doFilter(request, response);
            return;
        }

        String host = httpReq.getHeader("Host");
        String origin = httpReq.getHeader("Origin");
        if (hasMultipleHeaders(httpReq, "Host") || hasMultipleHeaders(httpReq, "Origin")) {
            httpRes.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid MCP Host or Origin");
            return;
        }
        if (!validHost(httpReq, host) || !validOrigin(httpReq, origin)) {
            httpRes.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid MCP Host or Origin");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isMcpPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return "/mcp".equals(path) || path.startsWith("/mcp/");
    }

    private static boolean hasMultipleHeaders(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (!values.hasMoreElements()) {
            return false;
        }
        values.nextElement();
        return values.hasMoreElements();
    }

    private static String configuredHost(String value) {
        String host = normalizeHost(value);
        if (host == null) {
            throw new IllegalArgumentException("Invalid mcp.transport.allowed-hosts entry");
        }
        return host;
    }

    private boolean validHost(HttpServletRequest request, String hostHeader) {
        // HTTP/1.0 clients may omit Host. Browsers capable of rebinding send it.
        if (hostHeader == null) {
            return true;
        }
        String host = normalizeHost(hostHeader);
        if (host == null) {
            return false;
        }
        if (!loopbackBind && additionalHosts.isEmpty()) {
            // A non-loopback deployment may use a TLS reverse proxy with its own
            // external Host; authentication and restricted Origin remain mandatory.
            return true;
        }
        return (loopbackBind
                        && LOOPBACK_HOSTS.contains(host)
                        && normalizeHost(hostHeader, request.getLocalPort()) != null)
                || additionalHosts.contains(host);
    }

    private boolean validOrigin(HttpServletRequest request, String originHeader) {
        if (originHeader == null) {
            return true;
        }
        URI origin;
        try {
            origin = URI.create(originHeader);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!origin.isAbsolute()
                || origin.getHost() == null
                || origin.getRawUserInfo() != null
                || (origin.getRawPath() != null && !origin.getRawPath().isEmpty())
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null) {
            return false;
        }

        if (cors.checkOrigin(originHeader) != null) {
            return true;
        }

        // A random local port (for example, integration tests) remains same-origin
        // without requiring a static CORS entry for every ephemeral port.
        return loopbackBind
                && LOOPBACK_HOSTS.contains(normalizeHost(origin.getHost()))
                && origin.getPort() == request.getLocalPort()
                && origin.getScheme().equalsIgnoreCase(request.isSecure() ? "https" : "http");
    }

    private static String normalizeHost(String authority) {
        return normalizeHost(authority, -1);
    }

    private static String normalizeHost(String authority, int requiredPort) {
        if (authority == null || authority.isBlank() || !authority.equals(authority.trim())) {
            return null;
        }
        String host = authority;
        String portText = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = authority.substring(0, end + 1);
            if (end + 1 < authority.length()) {
                if (authority.charAt(end + 1) != ':') {
                    return null;
                }
                portText = authority.substring(end + 2);
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                portText = authority.substring(colon + 1);
            }
        }
        if (!host.matches("(?i)(\\[[0-9a-f:]+]|[a-z0-9.-]+)")) {
            return null;
        }
        if (portText != null) {
            try {
                int port = Integer.parseInt(portText);
                if (port < 1 || port > 65535 || (requiredPort > 0 && port != requiredPort)) {
                    return null;
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }
}
