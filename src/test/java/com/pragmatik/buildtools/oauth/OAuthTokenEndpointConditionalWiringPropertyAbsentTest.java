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
 * Regression tests for issue #161 (property-absent case): when {@code
 * buildtools.oauth.token-endpoint.enabled} is not set at all, no OAuth token endpoint beans may
 * be registered ({@code matchIfMissing = false}).
 *
 * <p>{@code spring.config.name} points the bootstrap at a minimal properties file instead of the
 * shipped {@code application.properties}, so the token-endpoint property is genuinely absent
 * from the environment (inlined test properties would only override, not remove, it).
 */
@SpringBootTest(
        classes = BuildToolsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.name=application-absent-test")
@DisplayName("OAuthTokenEndpointConditionalWiringPropertyAbsent — issue #161 matchIfMissing")
class OAuthTokenEndpointConditionalWiringPropertyAbsentTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("property absent -> no OAuthTokenController, JwtTokenService or client registration bean")
    void noOAuthBeansWhenPropertyAbsent() {
        assertThat(context.getBeanNamesForType(OAuthTokenController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JwtTokenService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OAuthClientRegistrationRepository.class))
                .isEmpty();
    }
}
