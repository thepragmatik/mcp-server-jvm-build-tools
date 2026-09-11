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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Repository;

/**
 * Repository of statically configured OAuth 2.1 client registrations, loaded from
 * {@code buildtools.oauth.clients.*} configuration properties.
 *
 * <p>Client properties use Spring's relaxed-binding list-of-maps convention:
 *
 * <pre>{@code
 * buildtools.oauth.clients[0].client-id=ci-pipeline
 * buildtools.oauth.clients[0].client-secret=secret
 * buildtools.oauth.clients[0].scopes=build:execute,credential:read
 * buildtools.oauth.clients[0].grant-types=client_credentials
 * buildtools.oauth.clients[0].token-ttl-seconds=3600
 * }</pre>
 *
 * <p>No database dependency. For dynamic client registration, front with an external OAuth
 * authorization server (Keycloak, Okta, etc.).
 */
@ConditionalOnProperty(name = "buildtools.oauth.token-endpoint.enabled", havingValue = "true", matchIfMissing = false)
@Repository
@ConfigurationProperties(prefix = "buildtools.oauth")
public class OAuthClientRegistrationRepository implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientRegistrationRepository.class);

    /** List of client property maps, bound by Spring's {@link ConfigurationProperties}. */
    private List<Map<String, String>> clients = new ArrayList<>();

    /** Parsed client registrations, keyed by {@code client_id}. */
    private final Map<String, OAuthClientRegistration> registrations = new LinkedHashMap<>();

    /**
     * @return the raw client property entries (set by Spring from {@code buildtools.oauth.clients})
     */
    public List<Map<String, String>> getClients() {
        return clients;
    }

    /**
     * Called by Spring to set the raw client entries from {@code buildtools.oauth.clients}.
     *
     * @param clients a list of property maps, each representing one OAuth client
     */
    public void setClients(List<Map<String, String>> clients) {
        this.clients = clients == null ? new ArrayList<>() : clients;
    }

    @Override
    public void afterPropertiesSet() {
        for (Map<String, String> props : clients) {
            String clientId = props.get("client-id");
            if (clientId == null || clientId.isBlank()) {
                log.warn("Skipping OAuth client registration with missing client-id");
                continue;
            }
            String clientSecret = props.getOrDefault("client-secret", "");
            List<String> scopes = parseCsv(props.getOrDefault("scopes", ""));
            List<String> grantTypes = parseCsv(props.getOrDefault("grant-types", "client_credentials"));
            long ttl = parseLongOrDefault(props.getOrDefault("token-ttl-seconds", "3600"), 3600L);
            String jwkSetUri = props.getOrDefault("jwk-set-uri", "");

            OAuthClientRegistration registration =
                    new OAuthClientRegistration(clientId, clientSecret, scopes, grantTypes, ttl, jwkSetUri);
            registrations.put(clientId, registration);
            log.info(
                    "Registered OAuth client: {} (scopes={}, grant-types={}, ttl={}s)",
                    clientId,
                    scopes,
                    grantTypes,
                    ttl);
        }
        log.info("OAuth client registration: {} client(s) loaded", registrations.size());
    }

    /**
     * Find a client by its {@code client_id}.
     *
     * @param clientId the client identifier
     * @return an {@link Optional} containing the registration, or empty if not found
     */
    public Optional<OAuthClientRegistration> findByClientId(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(registrations.get(clientId));
    }

    /**
     * @return an immutable view of all registered clients, keyed by {@code client_id}
     */
    public Map<String, OAuthClientRegistration> allClients() {
        return Collections.unmodifiableMap(registrations);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String s : csv.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return List.copyOf(result);
    }

    private static long parseLongOrDefault(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
