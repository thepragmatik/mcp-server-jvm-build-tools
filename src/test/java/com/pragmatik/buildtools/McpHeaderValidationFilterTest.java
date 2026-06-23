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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

/**
 * Tests for {@link McpHeaderValidationFilter} — the additive, backward-compatible
 * {@code Mcp-Method}/{@code Mcp-Name} HeaderMismatch validator (SEP-2243).
 */
@DisplayName("McpHeaderValidationFilter")
class McpHeaderValidationFilterTest {

    private static final String SERVER_NAME = "MCP Server - Build Tools for the JVM Server";

    private McpHeaderValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new McpHeaderValidationFilter();
        ReflectionTestUtils.setField(filter, "serverName", SERVER_NAME);
    }

    private static MockHttpServletRequest mcpPost(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/mcp/message");
        req.setServletPath("/mcp/message");
        req.setContentType("application/json");
        if (body != null) {
            req.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return req;
    }

    private static String bodyOf(MockFilterChain chain) throws Exception {
        // The request seen downstream must replay the buffered body.
        return StreamUtils.copyToString(chain.getRequest().getInputStream(), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("pass-through (no rejection)")
    class PassThrough {

        @Test
        @DisplayName("absent MCP headers (legacy/older client) pass through unchanged")
        void absentHeadersPassThrough() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            MockHttpServletRequest req = mcpPost(body);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isNotNull();
            // Body must still be readable downstream.
            assertThat(bodyOf(chain)).isEqualTo(body);
        }

        @Test
        @DisplayName("matching Mcp-Method and Mcp-Name pass through and replay the body")
        void matchingHeadersPassThrough() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}";
            MockHttpServletRequest req = mcpPost(body);
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/call");
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_NAME, SERVER_NAME);
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(bodyOf(chain)).isEqualTo(body);
        }

        @Test
        @DisplayName("non-POST requests are ignored")
        void getRequestIgnored() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp/discover");
            req.setServletPath("/mcp/discover");
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "anything");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isSameAs(req);
        }

        @Test
        @DisplayName("non-/mcp POST requests are ignored")
        void nonMcpPathIgnored() throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/other");
            req.setServletPath("/api/other");
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/list");
            req.setContent("{\"method\":\"different\"}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(chain.getRequest()).isSameAs(req);
        }

        @Test
        @DisplayName("unparseable body is left to the transport (not rejected)")
        void unparseableBodyPassesThrough() throws Exception {
            MockHttpServletRequest req = mcpPost("this is not json");
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/list");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(bodyOf(chain)).isEqualTo("this is not json");
        }

        @Test
        @DisplayName("body without a method field is not a Mcp-Method mismatch")
        void bodyWithoutMethodNotRejected() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}";
            MockHttpServletRequest req = mcpPost(body);
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/list");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        @Test
        @DisplayName("non-HTTP response forces the early pass-through branch")
        void nonHttpPassesThrough() throws Exception {
            MockHttpServletRequest req = mcpPost("{\"method\":\"tools/list\"}");
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, new NotHttpResponse(), chain);

            assertThat(chain.getRequest()).isSameAs(req);
        }
    }

    @Nested
    @DisplayName("HeaderMismatch (rejection)")
    class Rejection {

        @Test
        @DisplayName("Mcp-Method header contradicting body method is rejected with 400")
        void mcpMethodMismatchRejected() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\"}";
            MockHttpServletRequest req = mcpPost(body);
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/list");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            assertThat(res.getContentType()).contains("application/json");
            String out = res.getContentAsString();
            assertThat(out).contains("HeaderMismatchError");
            assertThat(out).contains("\"id\":42");
            assertThat(out).contains("\"code\":-32600");
            // Request must NOT reach downstream.
            assertThat(chain.getRequest()).isNull();
        }

        @Test
        @DisplayName("Mcp-Name header contradicting server identity is rejected with 400")
        void mcpNameMismatchRejected() throws Exception {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/list\"}";
            MockHttpServletRequest req = mcpPost(body);
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_METHOD, "tools/list");
            req.addHeader(McpHeaderValidationFilter.HEADER_MCP_NAME, "some-other-server");
            MockHttpServletResponse res = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, res, chain);

            assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            String out = res.getContentAsString();
            assertThat(out).contains("HeaderMismatchError");
            assertThat(out).contains("\"id\":\"abc\"");
            assertThat(chain.getRequest()).isNull();
        }
    }

    @Nested
    @DisplayName("error envelope")
    class ErrorEnvelope {

        @Test
        @DisplayName("numeric id is emitted unquoted")
        void numericId() {
            String json = McpHeaderValidationFilter.buildErrorBody(5, "boom");
            assertThat(json).contains("\"id\":5");
            assertThat(json).contains("\"message\":\"HeaderMismatchError\"");
            assertThat(json).contains("\"detail\":\"boom\"");
        }

        @Test
        @DisplayName("string id is quoted and escaped")
        void stringId() {
            String json = McpHeaderValidationFilter.buildErrorBody("a\"b", "d");
            assertThat(json).contains("\"id\":\"a\\\"b\"");
        }

        @Test
        @DisplayName("null id is emitted as null")
        void nullId() {
            String json = McpHeaderValidationFilter.buildErrorBody(null, "d");
            assertThat(json).contains("\"id\":null");
        }
    }

    /** Minimal non-HTTP servlet response used to exercise the early pass-through branch. */
    private static final class NotHttpResponse implements jakarta.servlet.ServletResponse {
        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() {
            return null;
        }

        @Override
        public java.io.PrintWriter getWriter() {
            return null;
        }

        @Override
        public void setCharacterEncoding(String charset) {}

        @Override
        public void setContentLength(int len) {}

        @Override
        public void setContentLengthLong(long len) {}

        @Override
        public void setContentType(String type) {}

        @Override
        public void setBufferSize(int size) {}

        @Override
        public int getBufferSize() {
            return 0;
        }

        @Override
        public void flushBuffer() {}

        @Override
        public void resetBuffer() {}

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void reset() {}

        @Override
        public void setLocale(java.util.Locale loc) {}

        @Override
        public java.util.Locale getLocale() {
            return java.util.Locale.getDefault();
        }
    }
}
