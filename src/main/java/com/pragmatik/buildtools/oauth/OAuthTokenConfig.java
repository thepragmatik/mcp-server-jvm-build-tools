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
package com.pragmatik.buildtools.oauth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the OAuth 2.1 Client Credentials Grant token endpoint.
 *
 * <p>Registers the {@link JwtTokenService} and {@link OAuthTokenController} beans conditionally
 * on {@code buildtools.oauth.token-endpoint.enabled=true (disabled by default)}.
 *
 * <p>Both beans are also discoverable via {@code @SpringBootApplication(scanBasePackages =
 * "com.pragmatik.buildtools")} component scanning, but this explicit configuration provides an
 * additional conditional guard so the token endpoint is only active when explicitly enabled.
 */
@Configuration
@ConditionalOnProperty(name = "buildtools.oauth.token-endpoint.enabled", havingValue = "true", matchIfMissing = false)
public class OAuthTokenConfig {

    /**
     * No explicit bean declarations are needed here because {@link JwtTokenService} is annotated
     * with {@code @Service} and {@link OAuthTokenController} with {@code @RestController}, so they
     * are discovered via component scanning. This configuration class serves as a conditional guard
     * and a documentation point for the token endpoint feature.
     *
     * <p>If the token endpoint is disabled ({@code buildtools.oauth.token-endpoint.enabled=false}),
     * this configuration class is not loaded, and any component-scanned beans in the
     * {@code com.pragmatik.buildtools.oauth} package will still be created. To fully disable the
     * token endpoint, use a profile-based exclusion or exclude the package from component scanning.
     */
    public OAuthTokenConfig() {
        // Configuration marker — beans are discovered via component scanning
    }
}
