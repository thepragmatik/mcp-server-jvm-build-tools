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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    void dormantServiceMethodsRemainAbsentFromPublicMcpToolList() {
        String response = new RestTemplate()
                .postForObject("http://127.0.0.1:" + port + "/mcp", rpc("tools/list", "{}"), String.class);
        var catalog = new tools.jackson.databind.json.JsonMapper()
                .readTree(response)
                .get("result")
                .get("tools");
        List<String> names = new ArrayList<>();
        for (var tool : catalog) {
            names.add(tool.get("name").asText());
        }

        assertThat(names)
                .hasSize(24)
                .doesNotHaveDuplicates()
                .doesNotContain(
                        "execute_build_async",
                        "get_build_task",
                        "cancel_build_task",
                        "list_build_tasks",
                        "analyze_cache_health",
                        "optimize_build_cache",
                        "generate_sbom",
                        "audit_supply_chain",
                        "check_license_compliance",
                        "detect_flaky_tests",
                        "analyze_test_history");
    }

    @Test
    void listBuildToolsReturnsRegisteredNamesThroughMcp() {
        String response = new RestTemplate()
                .postForObject(
                        "http://127.0.0.1:" + port + "/mcp",
                        rpc("tools/call", "{\"name\":\"list_build_tools\",\"arguments\":{}}"),
                        String.class);
        assertThat(response).contains("maven", "gradle", "sbt").doesNotContain(System.getProperty("user.home"));
        assertThat(response).doesNotContain("clean, compile", "deploy", "install");
        var json = new tools.jackson.databind.json.JsonMapper().readTree(response);
        var content = json.get("result").get("content").get(0).get("text").asText();
        var result = new tools.jackson.databind.json.JsonMapper().readTree(content);
        assertThat(result.get("tools").size()).isEqualTo(3);
    }

    @Test
    void analysisSchemaAndStructuredResultReachHttpTransport() {
        RestTemplate client = new RestTemplate();
        String endpoint = "http://127.0.0.1:" + port + "/mcp";
        var json = new tools.jackson.databind.json.JsonMapper();
        var listed = json.readTree(client.postForObject(endpoint, rpc("tools/list", "{}"), String.class));
        var catalogue = listed.get("result").get("tools");
        tools.jackson.databind.JsonNode schema = null;
        for (var tool : catalogue) {
            if ("analyze_build_output".equals(tool.get("name").asText())) {
                schema = tool.get("outputSchema");
            }
        }
        assertThat(schema).isNotNull();
        assertThat(schema.get("required").get(0).asText()).isEqualTo("completed");
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();

        String response = client.postForObject(
                endpoint,
                rpc(
                        "tools/call",
                        "{\"name\":\"analyze_build_output\",\"arguments\":{\"buildToolName\":\"maven\",\"projectDir\":\".\",\"command\":\"validate\"}}"),
                String.class);
        var result = json.readTree(response).get("result");
        assertThat(result.get("structuredContent").get("completed").booleanValue())
                .isTrue();
        assertThat(json.readTree(result.get("content").get(0).get("text").asText()))
                .isEqualTo(result.get("structuredContent"));
        assertThat(response).doesNotContain(System.getProperty("user.home"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-f=", "--file=", "-s=", "--settings=", "--global-settings=", "--toolchains="})
    void mavenFileSelectorsAreDeniedWithoutEchoingPrivateInput(String option) {
        String canary = "../outside/private-canary/pom.xml";
        String response = new RestTemplate()
                .postForObject(
                        "http://127.0.0.1:" + port + "/mcp",
                        rpc(
                                "tools/call",
                                "{\"name\":\"execute_build_command\",\"arguments\":{\"buildToolName\":\"maven\",\"projectDir\":\".\",\"command\":\"validate "
                                        + option
                                        + canary + "\"}}"),
                        String.class);
        assertThat(response).contains("isError").doesNotContain(canary, "private-canary");
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
    void nativePromptsListAndGetUseServerAuthoredText() {
        RestTemplate client = new RestTemplate();
        String endpoint = "http://127.0.0.1:" + port + "/mcp";
        var mapper = new tools.jackson.databind.json.JsonMapper();
        var list = mapper.readTree(client.postForObject(endpoint, rpc("prompts/list", "{}"), String.class));
        var prompts = list.get("result").get("prompts");
        assertThat(prompts.size()).isEqualTo(3);
        assertThat(prompts.get(0).get("name").asText()).isEqualTo("diagnose_build_failure");

        String response = client.postForObject(
                endpoint, rpc("prompts/get", "{\"name\":\"diagnose_build_failure\"}"), String.class);
        assertThat(response)
                .contains("smallest relevant redacted diagnostic result")
                .doesNotContain(System.getProperty("user.home"));

        String canary = "private-canary@example.invalid";
        String invalid = client.postForObject(
                endpoint,
                rpc(
                        "prompts/get",
                        "{\"name\":\"diagnose_build_failure\",\"arguments\":{\"projectDir\":\"" + canary + "\"}}"),
                String.class);
        assertThat(invalid).contains("-32602", "Invalid params").doesNotContain(canary);
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
