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
 * Regression tests for issue #161: the OAuth token endpoint beans must honor {@code
 * buildtools.oauth.token-endpoint.enabled=false} (and absence of the property), not just the
 * {@link OAuthTokenConfig} guard.
 *
 * <p>Boots the full application the way a user would run it (component scanning via {@code
 * @SpringBootApplication(scanBasePackages = "com.pragmatik.buildtools")}) and asserts on the
 * resulting bean names in the {@link ApplicationContext}.
 */
@SpringBootTest(
        classes = BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.main.web-application-type=servlet",
            "buildtools.oauth.token-endpoint.enabled=false",
        })
@DisplayName("OAuthTokenEndpointConditionalWiring — issue #161 off-switch")
class OAuthTokenEndpointConditionalWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("enabled=false -> no OAuthTokenController, JwtTokenService or client registration bean")
    void noOAuthBeansWhenDisabled() {
        assertThat(context.getBeanNamesForType(OAuthTokenController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JwtTokenService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OAuthClientRegistrationRepository.class))
                .isEmpty();
        assertThat(context.containsBean("oAuthTokenController")).isFalse();
    }
}
