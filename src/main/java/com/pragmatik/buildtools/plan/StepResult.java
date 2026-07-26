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
package com.pragmatik.buildtools.plan;

import java.time.Instant;
import java.util.List;

/**
 * Per-step execution result within a plan.
 *
 * @param id              Step identifier
 * @param label           Human-readable step name
 * @param status          Execution status: "completed", "failed", "skipped", "running", "queued"
 * @param durationSeconds Step execution time in seconds
 * @param success         Whether the step completed successfully
 * @param output          Captured stdout (truncated to 10KB)
 * @param testSummary     Parsed test summary, if applicable (nullable)
 * @param errors          Build errors/warnings from this step
 * @param retryAttempted  Number of retries attempted
 * @param startedAt       Step start timestamp
 * @param finishedAt      Step completion timestamp
 */
public record StepResult(
        String id,
        String label,
        String status,
        double durationSeconds,
        boolean success,
        String output,
        TestSummary testSummary,
        List<BuildError> errors,
        int retryAttempted,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * Summary of test results parsed from build output.
     */
    public record TestSummary(int total, int passed, int failed, int errors, int skipped) {}

    /**
     * A build error or warning with source location.
     */
    public record BuildError(String file, int line, String severity, String message) {}
}
