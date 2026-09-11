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

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.application.BuildToolsApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Regression tests for issue #161 (positive case): with {@code
 * buildtools.oauth.token-endpoint.enabled=true} the OAuth token endpoint beans are registered.
 *
 * <p>Companion to {@link OAuthTokenEndpointConditionalWiringTest} which asserts the negative
 * (disabled) and property-absent cases.
 */
@SpringBootTest(
        classes = BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.web-application-type=servlet",
            "buildtools.oauth.token-endpoint.enabled=true",
            "buildtools.oauth.token-endpoint.issuer=https://test-issuer.example.com",
            "buildtools.oauth.token-endpoint.signing-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
            "buildtools.oauth.token-endpoint.default-ttl-seconds=3600",
            "buildtools.oauth.clients[0].client-id=test-client",
            "buildtools.oauth.clients[0].client-secret=test-secret",
            "buildtools.oauth.clients[0].scopes=build:read",
            "buildtools.oauth.clients[0].grant-types=client_credentials",
            "buildtools.oauth.clients[0].token-ttl-seconds=3600",
        })
@DisplayName("OAuthTokenEndpointConditionalWiringEnabled — issue #161 on-switch")
class OAuthTokenEndpointConditionalWiringEnabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("enabled=true -> OAuthTokenController, JwtTokenService and client repository registered")
    void oAuthBeansPresentWhenEnabled() {
        assertThat(context.getBeanNamesForType(OAuthTokenController.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(JwtTokenService.class)).isNotEmpty();
        assertThat(context.getBeanNamesForType(OAuthClientRegistrationRepository.class))
                .isNotEmpty();
    }
}
