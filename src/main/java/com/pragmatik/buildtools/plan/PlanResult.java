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
 * Aggregated result of executing a build plan.
 *
 * @param planId               Unique plan identifier
 * @param description          Plan description
 * @param status               Overall status: "completed", "failed", "cancelled", "running", "queued"
 * @param projectDir           Project directory path
 * @param tool                 Build tool used
 * @param totalDurationSeconds Total execution time in seconds
 * @param totalDurationFormatted Human-readable duration string
 * @param steps                Per-step results
 * @param summary              Aggregated summary counters
 * @param errors               All errors across all steps
 * @param finishedAt           Plan completion timestamp
 */
public record PlanResult(
        String planId,
        String description,
        String status,
        String projectDir,
        String tool,
        double totalDurationSeconds,
        String totalDurationFormatted,
        List<StepResult> steps,
        PlanSummary summary,
        List<StepResult.BuildError> errors,
        Instant finishedAt) {}
