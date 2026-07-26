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

import java.util.List;

/**
 * A single step within a build plan.
 *
 * @param id            Step identifier (e.g., "step-1", "compile")
 * @param label         Human-readable step name
 * @param command       Build command to execute (e.g., "clean compile", "test")
 * @param dependsOn     Step IDs that must complete before this step starts
 * @param timeoutSeconds Max execution time for this step (default: 300)
 * @param onFailure     Failure handling: "stop", "continue", or "skipRemaining" (default: "stop")
 * @param captureOutput Whether to capture and return full step output (default: true)
 * @param retryCount    Number of automatic retries on failure (default: 0, max: 3)
 */
public record PlanStep(
        String id,
        String label,
        String command,
        List<String> dependsOn,
        int timeoutSeconds,
        String onFailure,
        boolean captureOutput,
        int retryCount) {}
