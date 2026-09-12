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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Regression test for issue #197: the pom's {@code <resources>} include
 * whitelist silently excluded {@code application-http.properties},
 * {@code application-metrics.properties} and {@code logback-spring.xml} from
 * the packaged jar, so the documented {@code --spring.profiles.active=http}
 * transport could never bind a web server from the artifact.
 *
 * <p>Asserts the resources resolve on the runtime classpath (which for the
 * packaged jar means {@code BOOT-INF/classes}). Runs against
 * {@code target/classes} under {@code mvn test} and against the jar's
 * {@code BOOT-INF/classes} at runtime, both via the same classloader lookup.
 */
class ProfileResourcesPackagingTest {

    private static final String[] REQUIRED_RESOURCES = {
        "application.properties", "application-http.properties", "application-metrics.properties", "logback-spring.xml"
    };

    @Test
    void requiredSpringResourcesAreOnTheRuntimeClasspath() throws IOException {
        for (String resource : REQUIRED_RESOURCES) {
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                assertTrue(
                        in != null,
                        "required resource missing from runtime classpath: " + resource
                                + " (see issue #197 — the pom resources block must not "
                                + "whitelist-include files)");
                assertFalse(in.read() == -1, "resource present but empty: " + resource);
            }
        }
    }
}
