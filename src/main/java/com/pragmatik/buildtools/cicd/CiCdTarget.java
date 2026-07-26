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
package com.pragmatik.buildtools.cicd;

/**
 * SPI for CI/CD platform target implementations.
 *
 * <p>Each implementation translates a {@link CiCdPipeline} intermediate representation
 * into the platform's configuration format (YAML, JSON, etc.).
 *
 * <p>MVP: {@link GitHubActionsGenerator} implements this for GitHub Actions.
 * Future: GitLab CI, Jenkins, CircleCI, etc.
 */
public interface CiCdTarget {

    /** Platform name (e.g., "github-actions", "gitlab-ci", "jenkins"). */
    String name();

    /**
     * Generate platform-specific configuration from the pipeline model.
     *
     * @param pipeline  The intermediate pipeline representation
     * @return Platform-specific configuration as a string (YAML, etc.)
     */
    String generate(CiCdPipeline pipeline);

    /**
     * Validate a configuration string for syntax and required fields.
     *
     * @param configContent  Raw configuration content
     * @return Validation result
     */
    ValidationResult validate(String configContent);

    /** Result of a configuration validation. */
    record ValidationResult(boolean valid, String errorMessage) {

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }
}
