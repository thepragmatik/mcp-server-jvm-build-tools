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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CiCdValidator} — YAML parsing and schema validation.
 */
@DisplayName("CiCdValidator unit tests")
class CiCdValidatorTest {

    private final CiCdValidator validator = new CiCdValidator();

    @Nested
    @DisplayName("basic validation")
    class BasicValidationTests {

        @Test
        @DisplayName("validates well-formed YAML")
        void validatesWellFormedYaml() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("rejects null YAML")
        void rejectsNull() {
            CiCdValidator.ValidationResult result = validator.validate(null);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("empty");
        }

        @Test
        @DisplayName("rejects empty YAML")
        void rejectsEmpty() {
            CiCdValidator.ValidationResult result = validator.validate("");
            assertThat(result.valid()).isFalse();
        }

        @Test
        @DisplayName("rejects YAML missing name")
        void rejectsMissingName() {
            String yaml = "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Build\n"
                    + "        run: echo hello\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("name");
        }

        @Test
        @DisplayName("rejects YAML missing jobs")
        void rejectsMissingJobs() {
            String yaml = "name: CI\n" + "on:\n" + "  push:\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("jobs");
        }

        @Test
        @DisplayName("rejects YAML missing runs-on")
        void rejectsMissingRunsOn() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("runs-on");
        }

        @Test
        @DisplayName("rejects YAML without any steps")
        void rejectsMissingSteps() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "    branches: [main]\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("steps");
        }

        @Test
        @DisplayName("rejects YAML with empty steps")
        void rejectsEmptySteps() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps: []\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("trigger validation")
    class TriggerValidationTests {

        @Test
        @DisplayName("accepts valid schedule cron")
        void acceptsValidCron() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  schedule:\n"
                    + "    - cron: '0 9 * * 1'\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Build\n"
                    + "        run: echo hello\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("rejects schedule entry missing cron")
        void rejectsScheduleWithoutCron() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  schedule:\n"
                    + "    - branch: main\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Build\n"
                    + "        run: echo hello\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("cron");
        }
    }

    @Nested
    @DisplayName("step validation")
    class StepValidationTests {

        @Test
        @DisplayName("rejects duplicate step names within a job")
        void rejectsDuplicateStepNames() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Checkout\n"
                    + "        uses: actions/checkout@v4\n"
                    + "      - name: Checkout\n"
                    + "        run: echo again\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("Duplicate");
        }

        @Test
        @DisplayName("rejects step without uses or run")
        void rejectsStepWithoutUsesOrRun() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Empty step\n"
                    + "        id: noop\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("uses");
        }

        @Test
        @DisplayName("rejects invalid action reference format")
        void rejectsInvalidActionRef() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Bad action\n"
                    + "        uses: some-invalid-ref\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isFalse();
            assertThat(result.errorMessage()).contains("Invalid action reference");
        }

        @Test
        @DisplayName("accepts docker:// action references")
        void acceptsDockerActions() {
            String yaml = "name: CI\n"
                    + "on:\n"
                    + "  push:\n"
                    + "jobs:\n"
                    + "  build:\n"
                    + "    runs-on: ubuntu-latest\n"
                    + "    steps:\n"
                    + "      - name: Docker step\n"
                    + "        uses: docker://alpine:latest\n"
                    + "        run: echo hello\n";

            CiCdValidator.ValidationResult result = validator.validate(yaml);
            assertThat(result.valid()).isTrue();
        }
    }
}
