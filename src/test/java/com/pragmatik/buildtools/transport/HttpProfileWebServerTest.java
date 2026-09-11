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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Regression tests for issue #187: activating the documented {@code http} profile must start a real
 * web server (the base {@code application.properties} hard-codes
 * {@code spring.main.web-application-type=none}, which previously suppressed binding), while the
 * default (non-http) profile must remain stdio-only with no web server.
 */
class HttpProfileWebServerTest {

    /**
     * Starts the application with {@code spring.profiles.active=http} on a random port and asserts a
     * real TCP listener accepts connections and serves the health endpoint.
     */
    @SpringBootTest(
            classes = com.pragmatik.buildtools.application.BuildToolsApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.profiles.active=http", "server.port=0"})
    static class HttpProfileBindsWebServer {

        @LocalServerPort
        private int port;

        @Autowired
        private Environment environment;

        @Test
        void httpProfileBindsTcpListenerAndServesHealth() {
            assertThat(environment.getActiveProfiles()).contains("http");
            // The http profile must re-enable the servlet web server.
            assertThat(environment.getProperty("spring.main.web-application-type"))
                    .isEqualTo("servlet");
            assertThat(port).isPositive();

            // A real TCP connection must be accepted on the bound port.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 5000);
                assertThat(socket.isConnected()).isTrue();
            } catch (IOException e) {
                throw new AssertionError("No TCP listener on port " + port, e);
            }

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<String> health =
                    restTemplate.getForEntity("http://localhost:" + port + "/health", String.class);
            assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * Starts the application with no profile and asserts the intended stdio-only behaviour: no
     * servlet web server is started and nothing listens on a TCP port.
     */
    @SpringBootTest(
            classes = com.pragmatik.buildtools.application.BuildToolsApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {"spring.main.banner-mode=off"})
    static class DefaultProfileHasNoWebServer {

        @Autowired
        private ConfigurableApplicationContext applicationContext;

        @Autowired
        private Environment environment;

        @Test
        void defaultProfileRemainsNonWebAndBindsNoTcpPort() throws IOException {
            assertThat(environment.getProperty("spring.main.web-application-type"))
                    .isEqualTo("none");
            assertThat(applicationContext.containsBean("webServer")).isFalse();
            assertThat(applicationContext.containsBean("webServerStartStop")).isFalse();

            // A truly random port must be free: the application bound nothing.
            int probePort;
            try (ServerSocket probe = new ServerSocket(0)) {
                probePort = probe.getLocalPort();
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", probePort), 250);
                throw new AssertionError("Unexpected TCP listener bound while stdio-only");
            } catch (IOException expected) {
                // Nothing is listening — intended stdio-only behaviour.
            }
        }
    }
}
