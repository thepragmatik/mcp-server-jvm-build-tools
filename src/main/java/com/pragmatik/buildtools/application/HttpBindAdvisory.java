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
package com.pragmatik.buildtools.application;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs a prominent startup warning when the servlet (http profile) transport
 * is reachable beyond the loopback interface while tool authorization is
 * disabled — the unauthenticated-network-exposure posture flagged in the
 * security review of issue #199.
 *
 * <p>The bean is conditional on a live servlet web application, so stdio-mode
 * launches (no HTTP surface) never see the warning. Loopback detection
 * resolves {@code server.address} (when set) and compares it against the
 * loopback addresses; an unset {@code server.address} means the server binds
 * all interfaces ({@code 0.0.0.0} on IPv4), which is treated as non-loopback.
 * A hostname value is resolved via {@link InetAddress#getByName(String)},
 * which may perform a DNS lookup — literal IPs avoid that cost.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpBindAdvisory {

    private static final Logger log = LoggerFactory.getLogger(HttpBindAdvisory.class);

    private final String serverAddress;
    private final boolean authEnabled;

    HttpBindAdvisory(
            @Value("${server.address:}") String serverAddress,
            @Value("${buildtools.auth.enabled:false}") boolean authEnabled) {
        this.serverAddress = serverAddress;
        this.authEnabled = authEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void advise() {
        if (!authEnabled && !isLoopbackBind()) {
            log.warn("[security] HTTP transport is bound beyond loopback with tool authorization "
                    + "DISABLED — MCP endpoints are reachable from the network unauthenticated. "
                    + "Set server.address=127.0.0.1 (the safe default), or enable "
                    + "buildtools.auth.enabled=true / OAuth resource-server enforcement before "
                    + "exposing this server beyond localhost.");
        }
    }

    boolean isLoopbackBind() {
        if (serverAddress == null || serverAddress.isBlank()) {
            return false; // no explicit address -> binds 0.0.0.0 / all interfaces
        }
        try {
            return InetAddress.getByName(serverAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            log.warn("[security] could not resolve server.address='{}'; treating bind as non-loopback", serverAddress);
            return false;
        }
    }
}
