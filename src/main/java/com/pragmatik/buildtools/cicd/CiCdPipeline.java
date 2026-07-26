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

import java.util.List;
import java.util.Map;

/**
 * Intermediate representation of a CI/CD pipeline.
 *
 * <p>Captures all information needed to generate a platform-specific CI/CD configuration
 * (GitHub Actions YAML, GitLab CI, etc.). The model is platform-agnostic; a
 * {@link CiCdTarget} implementation translates it to the target format.
 */
public record CiCdPipeline(
        String name,
        String target,
        String description,
        CiCdTrigger on,
        Map<String, String> env,
        CiCdDefaults defaults,
        List<CiCdJob> jobs,
        CiCdPermissions permissions,
        List<CiCdSecret> requiredSecrets,
        String detectedBuildTool) {

    /** Convenience builder for tests. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link CiCdPipeline}. */
    public static final class Builder {
        private String name;
        private String target = "github-actions";
        private String description;
        private CiCdTrigger on;
        private Map<String, String> env = Map.of();
        private CiCdDefaults defaults;
        private List<CiCdJob> jobs = List.of();
        private CiCdPermissions permissions;
        private List<CiCdSecret> requiredSecrets = List.of();
        private String detectedBuildTool;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder on(CiCdTrigger on) {
            this.on = on;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder defaults(CiCdDefaults defaults) {
            this.defaults = defaults;
            return this;
        }

        public Builder jobs(List<CiCdJob> jobs) {
            this.jobs = jobs;
            return this;
        }

        public Builder permissions(CiCdPermissions permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder requiredSecrets(List<CiCdSecret> requiredSecrets) {
            this.requiredSecrets = requiredSecrets;
            return this;
        }

        public Builder detectedBuildTool(String detectedBuildTool) {
            this.detectedBuildTool = detectedBuildTool;
            return this;
        }

        public CiCdPipeline build() {
            return new CiCdPipeline(
                    name,
                    target,
                    description,
                    on,
                    env,
                    defaults,
                    jobs,
                    permissions,
                    requiredSecrets,
                    detectedBuildTool);
        }
    }
}
