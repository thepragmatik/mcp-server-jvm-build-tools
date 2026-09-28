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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PrivacySafeMcpJsonMapperTest {

    private final McpJsonMapper mapper = new PrivacySafeMcpJsonMapper(new JacksonMcpJsonMapper(new JsonMapper()));

    @Test
    void removesCallerTextAndErrorDataFromBothSerializationMethods() throws Exception {
        JSONRPCResponse error = JSONRPCResponse.error(
                7, new JSONRPCError(-32601, "Unknown synthetic-private-canary.invalid", "private detail"));

        String text = mapper.writeValueAsString(error);
        String bytes = new String(mapper.writeValueAsBytes(error), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(text).isEqualTo(bytes);
        assertThat(text).contains("\"id\":7", "Method not found");
        assertThat(text).doesNotContain("synthetic-private-canary.invalid", "private detail", "stackTrace");
    }

    @Test
    void keepsSuccessfulResultsIntact() throws Exception {
        JSONRPCResponse success = JSONRPCResponse.result(3, java.util.Map.of("value", "safe"));

        String text = mapper.writeValueAsString(success);

        assertThat(text).contains("\"id\":3", "\"value\":\"safe\"");
    }

    @Test
    void neverSerializesThrowableInternals() throws Exception {
        String text = mapper.writeValueAsString(new IllegalArgumentException("synthetic-private-canary.invalid"));

        assertThat(text).contains("Internal error");
        assertThat(text).doesNotContain("synthetic-private-canary.invalid", "stackTrace", "cause");
    }
}
