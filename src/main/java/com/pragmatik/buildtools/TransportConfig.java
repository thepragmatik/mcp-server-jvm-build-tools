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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Transport configuration for MCP streamable HTTP support.
 * <p>
 * Configures CORS for web-based MCP clients and dashboards,
 * and provides transport-level settings for the SSE event stream.
 */
@Configuration
public class TransportConfig {

    /**
     * CORS configuration allowing web-based MCP clients
     * to connect from any origin in development. In production,
     * restrict allowedOrigins to specific domains.
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/mcp/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders(
                                "Mcp-Method", "Mcp-Session-Id", "Content-Type", "Authorization", "Accept", "Origin")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }

    @Value("${buildtools.cache.ttl-ms:300000}")
    private long defaultTtlMs;

    public long getDefaultTtlMs() {
        return defaultTtlMs;
    }
}
