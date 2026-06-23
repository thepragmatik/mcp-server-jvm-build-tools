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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BuildAuthService}.
 * <p>
 * Tests credential masking, Maven settings.xml parsing, Gradle properties
 * parsing, and the credential status check tool.
 */
@DisplayName("BuildAuthService unit tests")
class BuildAuthServiceTest {

    private final BuildAuthService service = new BuildAuthService();

    // Credential masking

    @Nested
    @DisplayName("Credential masking")
    class CredentialMasking {

        @Test
        @DisplayName("masks password showing only last 3 chars")
        void masksPasswordShowingLast3Chars() {
            String masked = BuildAuthService.maskCredential("s3cret-password-abc");
            assertThat(masked).isEqualTo("****abc");
        }

        @Test
        @DisplayName("masks short passwords fully")
        void masksShortPasswords() {
            String masked = BuildAuthService.maskCredential("ab");
            assertThat(masked).isEqualTo("****");
        }

        @Test
        @DisplayName("handles exactly 3 character passwords")
        void handlesExact3CharPasswords() {
            String masked = BuildAuthService.maskCredential("xyz");
            assertThat(masked).isEqualTo("****");
        }

        @Test
        @DisplayName("handles null gracefully")
        void handlesNullGracefully() {
            assertThat(BuildAuthService.maskCredential(null)).isNull();
        }

        @Test
        @DisplayName("handles blank string gracefully")
        void handlesBlankGracefully() {
            assertThat(BuildAuthService.maskCredential("   ")).isNull();
        }

        @Test
        @DisplayName("never exposes full password")
        void neverExposesFullPassword() {
            String masked = BuildAuthService.maskCredential("my-very-secret-password");
            assertThat(masked).doesNotContain("very-secret");
            assertThat(masked).startsWith("****");
            assertThat(masked).hasSize(7); // "****" + 3 chars
        }
    }

    // Maven settings.xml parsing

    @Nested
    @DisplayName("Maven settings.xml parsing")
    class MavenSettingsParsing {

        @Test
        @DisplayName("parses servers with masked passwords")
        void parsesServersWithMaskedPasswords() throws Exception {
            Path settingsFile = Paths.get("src/test/resources/test-settings/settings-with-servers.xml");
            Map<String, Object> result = service.parseMavenSettings(settingsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
            assertThat(servers).hasSize(3);

            // First server: central-repo
            Map<String, Object> server1 = servers.get(0);
            assertThat(server1.get("id")).isEqualTo("central-repo");
            assertThat(server1.get("username")).isEqualTo("deploy-user");
            assertThat(server1.get("password")).isEqualTo("****abc");

            // Second server: private-nexus
            Map<String, Object> server2 = servers.get(1);
            assertThat(server2.get("id")).isEqualTo("private-nexus");
            assertThat(server2.get("password")).isEqualTo("****789");
        }

        @Test
        @DisplayName("parses mirror configurations")
        void parsesMirrorConfigurations() throws Exception {
            Path settingsFile = Paths.get("src/test/resources/test-settings/settings-with-servers.xml");
            Map<String, Object> result = service.parseMavenSettings(settingsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mirrors = (List<Map<String, Object>>) result.get("mirrors");
            assertThat(mirrors).hasSize(2);

            Map<String, Object> mirror1 = mirrors.get(0);
            assertThat(mirror1.get("id")).isEqualTo("corporate-mirror");
            assertThat(mirror1.get("url")).isEqualTo("https://maven.corp.example.com/repository/public");
            assertThat(mirror1.get("mirrorOf")).isEqualTo("central");

            Map<String, Object> mirror2 = mirrors.get(1);
            assertThat(mirror2.get("id")).isEqualTo("internal-mirror");
            assertThat(mirror2.get("mirrorOf")).isEqualTo("*");
        }

        @Test
        @DisplayName("parses proxy settings with masked credentials")
        void parsesProxySettingsWithMaskedCredentials() throws Exception {
            Path settingsFile = Paths.get("src/test/resources/test-settings/settings-with-servers.xml");
            Map<String, Object> result = service.parseMavenSettings(settingsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> proxies = (List<Map<String, Object>>) result.get("proxies");
            assertThat(proxies).hasSize(2);

            Map<String, Object> proxy1 = proxies.get(0);
            assertThat(proxy1.get("id")).isEqualTo("corp-proxy");
            assertThat(proxy1.get("active")).isEqualTo(true);
            assertThat(proxy1.get("protocol")).isEqualTo("https");
            assertThat(proxy1.get("host")).isEqualTo("proxy.corp.example.com");
            assertThat(proxy1.get("port")).isEqualTo(8080);
            assertThat(proxy1.get("username")).isEqualTo("proxy-user");
            assertThat(proxy1.get("password")).isEqualTo("****ret");
            assertThat(proxy1.get("nonProxyHosts")).isEqualTo("localhost|*.internal.example.com");

            Map<String, Object> proxy2 = proxies.get(1);
            assertThat(proxy2.get("id")).isEqualTo("alt-proxy");
            assertThat(proxy2.get("active")).isEqualTo(false);
        }

        @Test
        @DisplayName("handles empty settings.xml")
        void handlesEmptySettingsXml() throws Exception {
            Path settingsFile = Paths.get("src/test/resources/test-settings/settings-empty.xml");
            Map<String, Object> result = service.parseMavenSettings(settingsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> servers = (List<Map<String, Object>>) result.get("servers");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mirrors = (List<Map<String, Object>>) result.get("mirrors");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> proxies = (List<Map<String, Object>>) result.get("proxies");

            assertThat(servers).isEmpty();
            assertThat(mirrors).isEmpty();
            assertThat(proxies).isEmpty();
        }
    }

    // Gradle properties parsing

    @Nested
    @DisplayName("Gradle properties parsing")
    class GradlePropertiesParsing {

        @Test
        @DisplayName("parses credential properties with masked values")
        void parsesCredentialPropertiesWithMaskedValues() throws Exception {
            Path propsFile = Paths.get("src/test/resources/test-settings/gradle-with-creds.properties");
            Map<String, Object> result = service.parseGradleProperties(propsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> credentials = (List<Map<String, Object>>) result.get("credentials");
            assertThat(credentials).isNotEmpty();

            // Should have repo URL
            @SuppressWarnings("unchecked")
            Map<String, String> repoUrls = (Map<String, String>) result.get("repoUrls");
            assertThat(repoUrls).containsKey("systemProp.reposilite.repoUrl");
            assertThat(repoUrls.get("systemProp.reposilite.repoUrl"))
                    .isEqualTo("https://reposilite.internal.example.com/releases");
        }

        @Test
        @DisplayName("masks credential values in gradle properties")
        void masksCredentialValues() throws Exception {
            Path propsFile = Paths.get("src/test/resources/test-settings/gradle-with-creds.properties");
            Map<String, Object> result = service.parseGradleProperties(propsFile);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> credentials = (List<Map<String, Object>>) result.get("credentials");

            // All credential values should start with "****"
            for (Map<String, Object> cred : credentials) {
                String value = (String) cred.get("value");
                assertThat(value).startsWith("****");
            }
        }
    }

    // Credential status check tool

    @Nested
    @DisplayName("check_credential_status tool")
    class CredentialStatusCheck {

        @Test
        @DisplayName("returns success status with scope")
        void returnsSuccessStatusWithScope() {
            String result = service.checkCredentialStatus(null, "maven");
            assertThat(result).contains("\"status\"");
            assertThat(result).contains("\"scope\"");
            assertThat(result).contains("\"maven\"");
        }

        @Test
        @DisplayName("defaults scope to all when null")
        void defaultsScopeToAllWhenNull() {
            String result = service.checkCredentialStatus(null, null);
            assertThat(result).contains("\"all\"");
        }

        @Test
        @DisplayName("includes maven section when scope is maven")
        void includesMavenSectionWhenScopeIsMaven() {
            String result = service.checkCredentialStatus(null, "maven");
            assertThat(result).contains("\"maven\"");
        }

        @Test
        @DisplayName("includes gradle section when scope is gradle")
        void includesGradleSectionWhenScopeIsGradle() {
            String result = service.checkCredentialStatus(null, "gradle");
            assertThat(result).contains("\"gradle\"");
        }

        @Test
        @DisplayName("includes environment section when scope is all")
        void includesEnvironmentSectionWhenScopeIsAll() {
            String result = service.checkCredentialStatus(null, "all");
            assertThat(result).contains("\"environment\"");
        }

        @Test
        @DisplayName("never exposes raw passwords in JSON response")
        void neverExposesRawPasswords() {
            String result = service.checkCredentialStatus(null, "all");
            assertThat(result).doesNotContain("s3cret");
            assertThat(result).doesNotContain("n3xus");
        }

        @Test
        @DisplayName("includes summary section")
        void includesSummarySection() {
            String result = service.checkCredentialStatus(null, "all");
            assertThat(result).contains("\"summary\"");
            assertThat(result).contains("\"sourcesChecked\"");
            assertThat(result).contains("\"sourcesConfigured\"");
            assertThat(result).contains("\"gaps\"");
            assertThat(result).contains("\"recommendation\"");
        }

        @Test
        @DisplayName("result is valid JSON")
        void resultIsValidJson() {
            String result = service.checkCredentialStatus(null, "all");
            assertThat(result).startsWith("{");
            assertThat(result).endsWith("}");
        }
    }
}
