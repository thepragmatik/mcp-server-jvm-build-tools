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

import java.util.Map;

/**
 * Individual step in a CI job.
 *
 * @param name              Step display name
 * @param uses              Action reference (e.g., "actions/checkout@v4")
 * @param run               Shell command to execute
 * @param with              Action parameters
 * @param env               Step-specific environment variables
 * @param condition         Step condition expression
 * @param continueOnError   Whether to continue on failure
 * @param timeoutMinutes    Step timeout in minutes
 * @param workingDirectory  Working directory for the step
 */
public record CiCdStep(
        String name,
        String uses,
        String run,
        Map<String, Object> with,
        Map<String, String> env,
        String condition,
        boolean continueOnError,
        Integer timeoutMinutes,
        String workingDirectory) {

    /** Create a checkout step. */
    public static CiCdStep checkout() {
        return new CiCdStep("Checkout code", "actions/checkout@v4", null, Map.of(), Map.of(), null, false, null, null);
    }

    /** Create a run step. */
    public static CiCdStep run(String name, String command) {
        return new CiCdStep(name, null, command, Map.of(), Map.of(), null, false, null, null);
    }

    /** Create a setup-java step. */
    public static CiCdStep setupJava(String javaVersion) {
        return new CiCdStep(
                "Set up JDK " + javaVersion,
                "actions/setup-java@v4",
                null,
                Map.of("distribution", "temurin", "java-version", "${{ matrix.java }}"),
                Map.of(),
                null,
                false,
                null,
                null);
    }

    /** Create a cache step. */
    public static CiCdStep cache(String name, String path, String key, String restoreKeys) {
        return new CiCdStep(
                name,
                "actions/cache@v4",
                null,
                Map.of("path", path, "key", key, "restore-keys", restoreKeys),
                Map.of(),
                null,
                false,
                null,
                null);
    }

    /** Create an upload-artifact step. */
    public static CiCdStep uploadArtifact(String name, String path) {
        return new CiCdStep(
                name,
                "actions/upload-artifact@v4",
                null,
                Map.of("name", name, "path", path),
                Map.of(),
                null,
                false,
                null,
                null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String uses;
        private String run;
        private Map<String, Object> with = Map.of();
        private Map<String, String> env = Map.of();
        private String condition;
        private boolean continueOnError;
        private Integer timeoutMinutes;
        private String workingDirectory;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder uses(String uses) {
            this.uses = uses;
            return this;
        }

        public Builder run(String run) {
            this.run = run;
            return this;
        }

        public Builder with(Map<String, Object> with) {
            this.with = with;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public Builder continueOnError(boolean continueOnError) {
            this.continueOnError = continueOnError;
            return this;
        }

        public Builder timeoutMinutes(Integer timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
            return this;
        }

        public Builder workingDirectory(String workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public CiCdStep build() {
            return new CiCdStep(
                    name, uses, run, with, env, condition, continueOnError, timeoutMinutes, workingDirectory);
        }
    }
}
