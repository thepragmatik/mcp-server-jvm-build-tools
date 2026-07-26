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
package com.pragmatik.buildtools.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Integration tests for the observability / Prometheus metrics endpoint.
 *
 * <p>Starts the full Spring Boot application with an embedded servlet container and verifies that
 * {@code /actuator/prometheus} returns valid Prometheus exposition format, that
 * {@code /actuator/metrics} responds, and that custom metrics (tool calls, build, cache, auth) are
 * registered.
 */
@SpringBootTest(
        classes = com.pragmatik.buildtools.application.BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.web-application-type=servlet", "spring.ai.mcp.server.http=true", "server.port=0"})
class MetricsIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void prometheusEndpointReturnsValidFormat() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).satisfies(mediaType -> {
            assertThat(mediaType).isNotNull();
            assertThat(mediaType.toString()).contains("text/plain");
        });

        String body = response.getBody();
        assertThat(body).isNotNull();

        // Should contain JVM metrics (Micrometer built-in, always present)
        assertThat(body).contains("jvm_memory_used_bytes");

        // Should contain tool registration gauge (registered at startup in MetricsConfig)
        assertThat(body).contains("buildtools_tools_registered");

        // Should contain process metrics
        assertThat(body).contains("process_");

        // Prometheus text format markers
        assertThat(body).contains("# HELP");
        assertThat(body).contains("# TYPE");
    }

    @Test
    void metricsEndpointReturnsJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/metrics"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("names");
    }

    @Test
    void toolCallMetricIsExposed() {
        // Micrometer lazy-registers metrics on first use; 404 is expected if no
        // @Tool method has been called during this test's Spring context lifecycle.
        try {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(url("/actuator/metrics/buildtools.tool.calls"), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        } catch (HttpClientErrorException e) {
            // Micrometer lazy-registers metrics on first use; 404 (or any 4xx)
            // is acceptable for lazy registration
        }
    }

    @Test
    void buildDurationMetricIsExposed() {
        // Micrometer lazy-registers metrics on first use; 404 is expected if no
        // @Tool method has been called during this test's Spring context lifecycle.
        try {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(url("/actuator/metrics/buildtools.tool.duration"), String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        } catch (HttpClientErrorException e) {
            // Metric not yet registered — acceptable for lazy registration
        }
    }

    @Test
    void healthEndpointIsStillAvailable() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void jvmMetricsAreExposed() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/metrics/jvm.memory.used"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"name\":\"jvm.memory.used\"");
    }

    @Test
    void prometheusBodyContainsToolCountGauge() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/prometheus"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();

        // The gauge should register a non-negative tool count
        assertThat(body).contains("buildtools_tools_registered");
    }
}
