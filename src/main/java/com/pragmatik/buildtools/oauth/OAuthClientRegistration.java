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

import java.util.List;

/**
 * A registered OAuth 2.1 client, loaded from {@code buildtools.oauth.clients.*} configuration
 * properties. Each client has a {@code client_id}, a {@code client_secret} (plaintext for
 * {@code client_secret_basic}), a list of allowed scopes, supported grant types, and an
 * optional JWK Set URI for {@code private_key_jwt} client authentication.
 *
 * <p>For the MVP the client secret is stored in plaintext in application properties (see the
 * spec decision log). Production deployments should front this endpoint with a reverse proxy
 * that handles client authentication externally.
 */
public record OAuthClientRegistration(
        String clientId,
        String clientSecret,
        List<String> scopes,
        List<String> grantTypes,
        long tokenTtlSeconds,
        String jwkSetUri) {

    /**
     * @return {@code true} when this client supports {@code private_key_jwt} client authentication
     *     (indicated by a non-blank {@code jwkSetUri})
     */
    public boolean supportsPrivateKeyJwt() {
        return jwkSetUri != null && !jwkSetUri.isBlank();
    }
}
