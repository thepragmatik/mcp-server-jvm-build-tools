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
 * A multi-step build plan with ordered steps and failure handling.
 *
 * @param planId        Unique plan identifier (UUID)
 * @param description   Human-readable description of the plan's goal
 * @param projectDir    Project directory path
 * @param buildToolName Build tool override (nullable — auto-detect if null)
 * @param buildToolHome Build tool installation path (nullable)
 * @param steps         Ordered list of build steps to execute
 * @param errorHandling Default failure handling: "stop", "continue", or "skipRemaining" (default: "stop")
 * @param createdAt     Plan creation timestamp
 * @param ttlSeconds    Plan expiry in seconds (default: 3600)
 */
public record BuildPlan(
        String planId,
        String description,
        String projectDir,
        String buildToolName,
        String buildToolHome,
        List<PlanStep> steps,
        String errorHandling,
        Instant createdAt,
        int ttlSeconds) {}
