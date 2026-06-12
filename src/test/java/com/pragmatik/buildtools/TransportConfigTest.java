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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for streamable HTTP transport configuration, CORS,
 * request logging, and SSE event broadcasting.
 */
class TransportConfigTest {

    @Test
    void transportConfig_beanExists() {
        TransportConfig config = new TransportConfig();
        assertNotNull(config.corsConfigurer());
    }

    @Test
    void buildEventController_initialSubscriberCount() {
        BuildEventController controller = new BuildEventController();
        String result = controller.subscriberCount();
        assertTrue(result.contains("\"subscribers\":0"));
        assertTrue(result.contains("/mcp/build-events/stream"));
    }

    @Test
    void buildEventController_createsSseEmitter() {
        BuildEventController controller = new BuildEventController();
        var emitter = controller.stream();
        assertNotNull(emitter);
        assertEquals(30 * 60 * 1000L, emitter.getTimeout());
    }

    @Test
    void buildEventController_broadcastToNone() {
        BuildEventController controller = new BuildEventController();
        // Broadcasting with no subscribers should not throw
        assertDoesNotThrow(() ->
            controller.broadcast("build-start", "{\"status\":\"started\"}"));
    }

    @Test
    void transportLoggingFilter_logsWithoutError() throws Exception {
        TransportLoggingFilter filter = new TransportLoggingFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp/health");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        assertDoesNotThrow(() -> filter.doFilter(req, res, chain));
    }
}
