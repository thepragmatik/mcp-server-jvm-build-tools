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

import com.pragmatik.buildtools.application.BuildToolsApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(
        classes = BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=http",
            "server.port=0",
            "buildtools.oauth.resource-server.enabled=false",
            "buildtools.projects.allowed-roots=${user.dir}"
        })
class HttpMcpProtocolDispatchTest {
    @LocalServerPort
    private int port;

    @Test
    void rejectsDnsRebindingHeadersBeforeUnauthenticatedMcpDispatch() throws Exception {
        assertThat(RawMcpRequest.postStatus(port, "evil.example.test:" + port, "http://evil.example.test"))
                .isEqualTo(403);
        assertThat(RawMcpRequest.postStatus(port, "evil.example.test:" + port, null))
                .isEqualTo(403);
        assertThat(RawMcpRequest.postStatus(port, "127.0.0.1:" + port, null)).isEqualTo(200);
        assertThat(RawMcpRequest.postStatus(port, "127.0.0.1:" + port, "http://localhost:8080"))
                .isEqualTo(200);
        assertThat(RawMcpRequest.postStatus(port, "127.0.0.1:" + port, "http://127.0.0.1:" + port))
                .isEqualTo(200);
    }

    @Test
    void servesActualMcpToolListAndCall() {
        RestTemplate client = new RestTemplate();
        String endpoint = "http://127.0.0.1:" + port + "/mcp";
        String tools = client.postForObject(endpoint, rpc("tools/list", "{}"), String.class);
        assertThat(tools).contains("\"tools\"").contains("detect_build_tool").doesNotContain("Method not found");
        assertThat(tools)
                .contains("Return dependency, managed-entry, and BOM counts")
                .contains("Return scanned, vulnerable, critical, and high counts");

        String result = client.postForObject(
                endpoint,
                rpc("tools/call", "{\"name\":\"detect_build_tool\",\"arguments\":{\"projectDir\":\".\"}}"),
                String.class);
        assertThat(result).contains("detectedTools").contains("maven").doesNotContain(System.getProperty("user.home"));
    }

    @Test
    void deniedPathDoesNotEchoPrivateInput() {
        String canary = "/private/tmp/alice@example.invalid/secret-canary";
        String result = new RestTemplate()
                .postForObject(
                        "http://127.0.0.1:" + port + "/mcp",
                        rpc(
                                "tools/call",
                                "{\"name\":\"detect_build_tool\",\"arguments\":{\"projectDir\":\"" + canary + "\"}}"),
                        String.class);
        assertThat(result).contains("isError").doesNotContain(canary, "alice@example.invalid", "secret-canary");
    }

    @Test
    void promptCallReturnsUsefulWorkflowWithoutEchoingInput() {
        String result = new RestTemplate()
                .postForObject(
                        "http://127.0.0.1:" + port + "/mcp",
                        rpc(
                                "tools/call",
                                "{\"name\":\"prompt_build_diagnosis\",\"arguments\":{\"projectDir\":\".\",\"failedCommand\":\"SYNTHETIC SECRET COMMAND\"}}"),
                        String.class);
        assertThat(result)
                .contains("Follow this diagnostic workflow")
                .doesNotContain("SYNTHETIC SECRET COMMAND", System.getProperty("user.home"));
    }

    @Test
    void resourceListingReturnsSafeActionableSummary() {
        String result = new RestTemplate()
                .postForObject(
                        "http://127.0.0.1:" + port + "/mcp",
                        rpc(
                                "tools/call",
                                "{\"name\":\"list_dependency_resources\",\"arguments\":{\"projectDir\":\".\"}}"),
                        String.class);
        assertThat(result)
                .contains("availableBuildTools", "maven", "resourceCount")
                .doesNotContain(System.getProperty("user.home"));
    }

    @Test
    void rejectsOversizedHeaderlessPostBeforeSdkDispatch() {
        assertOversizedPostRejected(12 * 1024 * 1024, false);
    }

    @Test
    void rejectsOversizedHeaderedPostBeforeSdkDispatch() {
        assertOversizedPostRejected(2 * 1024 * 1024, true);
    }

    private void assertOversizedPostRejected(int bytes, boolean withMethodHeader) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withMethodHeader) {
            headers.add("Mcp-Method", "tools/list");
        }
        HttpEntity<String> request = new HttpEntity<>("x".repeat(bytes), headers);
        try {
            new RestTemplate().postForEntity("http://127.0.0.1:" + port + "/mcp", request, String.class);
            org.junit.jupiter.api.Assertions.fail("Expected HTTP 413");
        } catch (HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(413);
            assertThat(e.getResponseBodyAsString()).contains("PayloadTooLargeError");
        }
    }

    private static HttpEntity<String> rpc(String method, String params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON, MediaType.parseMediaType("text/event-stream")));
        return new HttpEntity<>(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}", headers);
    }
}
