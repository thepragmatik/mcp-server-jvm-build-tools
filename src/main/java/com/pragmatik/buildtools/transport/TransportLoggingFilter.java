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

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Lightweight HTTP request/response logging filter for MCP transport.
 * <p>
 * Logs method, URI, status code, and elapsed time for each HTTP request
 * to MCP endpoints. Active only when the HTTP transport profile is active.
 */
@Component
@Order(1)
public class TransportLoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TransportLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        long start = System.currentTimeMillis();

        try {
            chain.doFilter(request, response);
        } finally {
            HttpServletResponse httpRes = (HttpServletResponse) response;
            long elapsed = System.currentTimeMillis() - start;
            String mcpMethod = httpReq.getHeader("Mcp-Method");
            log.debug(
                    "{} {} -> {} ({}ms) Mcp-Method={}",
                    httpReq.getMethod(),
                    httpReq.getRequestURI(),
                    httpRes.getStatus(),
                    elapsed,
                    mcpMethod != null ? mcpMethod : "none");
        }
    }
}
