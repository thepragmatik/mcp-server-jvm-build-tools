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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.pragmatik.buildtools.security.OAuthResourceServerConfig;
import com.pragmatik.buildtools.security.ProjectAccessPolicy;
import com.pragmatik.buildtools.security.ToolAuthorizationService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpExposureGuardTest {
    @TempDir
    Path temporary;

    @AfterEach
    void clearKey() {
        System.clearProperty("buildtools.api.key.local");
        System.clearProperty("buildtools.api.key.local.scopes");
    }

    @Test
    void loopbackIsAllowedForLocalConfiguration() {
        var guard = new TransportConfig()
                .httpExposureGuard(
                        "127.0.0.1",
                        new ProjectAccessPolicy(""),
                        new OAuthResourceServerConfig(false, "", List.of()),
                        new ToolAuthorizationService());
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }

    @Test
    void exposedBindFailsWithoutAllControls() {
        var guard = new TransportConfig()
                .httpExposureGuard(
                        "0.0.0.0",
                        new ProjectAccessPolicy(""),
                        new OAuthResourceServerConfig(false, "", List.of()),
                        new ToolAuthorizationService());
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
    }

    @Test
    void exposedBindAcceptsExplicitControls() {
        System.setProperty("buildtools.api.key.local", "integration-test-only");
        System.setProperty("buildtools.api.key.local.scopes", "build:read");
        var guard = new TransportConfig()
                .httpExposureGuard(
                        "0.0.0.0",
                        new ProjectAccessPolicy(temporary.toString()),
                        new OAuthResourceServerConfig(true, "", List.of()),
                        new ToolAuthorizationService());
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }
}
