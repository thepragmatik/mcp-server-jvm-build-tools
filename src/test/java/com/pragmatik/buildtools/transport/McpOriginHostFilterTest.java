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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class McpOriginHostFilterTest {

    private final TransportConfig config = new TransportConfig();
    private final McpOriginHostFilter filter = new McpOriginHostFilter(config, "127.0.0.1", "");

    @Test
    void rejectsReboundHostAndOrigin() throws Exception {
        assertRejected("evil.example.test:9123", "http://evil.example.test", 9123);
        assertRejected("localhost.evil.example.test:9123", null, 9123);
        assertRejected("localhost:9999", null, 9123);
        assertRejected("127.0.0.1:9123", "http://evil.example.test", 9123);
    }

    @Test
    void acceptsLocalHostWithMissingOrSameOrigin() throws Exception {
        assertAccepted("127.0.0.1:9123", null, 9123);
        assertAccepted("localhost:9123", "http://localhost:9123", 9123);
        assertAccepted("[::1]:9123", "http://[::1]:9123", 9123);
    }

    @Test
    void acceptsConfiguredBrowserOriginAndRejectsMalformedOrigin() throws Exception {
        ReflectionTestUtils.setField(config, "corsAllowedOrigins", "https://dashboard.example.test");
        McpOriginHostFilter configured = new McpOriginHostFilter(config, "127.0.0.1", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        configured.doFilter(request("127.0.0.1:9123", "https://dashboard.example.test", 9123), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertRejected("127.0.0.1:9123", "null", 9123);
        assertRejected("127.0.0.1:9123", "http://localhost:9123/path", 9123);
    }

    @Test
    void permitsReverseProxyHostOnlyWhenExplicitlyConfigured() throws Exception {
        McpOriginHostFilter configured = new McpOriginHostFilter(config, "127.0.0.1", "proxy.example.test");
        MockHttpServletRequest request = request("proxy.example.test:9123", null, 9123);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        configured.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private void assertRejected(String host, String origin, int port) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(host, origin, port), response, chain);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    private void assertAccepted(String host, String origin, int port) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(host, origin, port), response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String host, String origin, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setLocalPort(port);
        request.addHeader("Host", host);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }
}
