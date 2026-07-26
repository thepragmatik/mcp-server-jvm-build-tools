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

/**
 * Aggregated summary counters for a plan execution result.
 *
 * @param total        Total number of steps
 * @param completed    Steps completed successfully
 * @param failed       Steps that failed
 * @param skipped      Steps that were skipped
 * @param errorCount   Total error count across all steps
 * @param warningCount Total warning count across all steps
 */
public record PlanSummary(int total, int completed, int failed, int skipped, int errorCount, int warningCount) {}
