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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Validates GitHub Actions workflow YAML for syntax correctness and required field presence.
 *
 * <p>Performs:
 * <ul>
 *   <li>YAML syntax parsing (via SnakeYAML)</li>
 *   <li>Required field checks: {@code name}, {@code on}, {@code runs-on}, {@code steps}</li>
 *   <li>Trigger validity: non-empty branch lists for push/PR, valid cron expressions</li>
 *   <li>Step name uniqueness within a job</li>
 *   <li>Action reference format validation ({@code owner/repo@ref})</li>
 * </ul>
 */
@Component
public class CiCdValidator {

    private static final Pattern CRON_PATTERN = Pattern.compile("^(\\*|[0-9]+(/[0-9]+)?)\\s"
            + "(\\*|[0-9]+(/[0-9]+)?)\\s"
            + "(\\*|[0-9]+(/[0-9]+)?)\\s"
            + "(\\*|[0-9]+(/[0-9]+)?)\\s"
            + "(\\*|[0-9]+(/[0-9]+)?)");

    private static final Pattern ACTION_REF_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+@[a-zA-Z0-9._-]+$");

    private static final Set<String> KNOWN_SHELLS = Set.of("bash", "sh", "pwsh", "python", "powershell", "cmd");

    /** Result of a CI config validation. */
    public record ValidationResult(boolean valid, String errorMessage) {

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }

    /**
     * Validate a GitHub Actions workflow YAML string.
     *
     * @param configContent Raw YAML content
     * @return Validation result with error details if invalid
     */
    @SuppressWarnings("unchecked")
    public ValidationResult validate(String configContent) {
        if (configContent == null || configContent.isBlank()) {
            return ValidationResult.error("Configuration content is empty");
        }

        // Parse YAML
        Map<String, Object> root;
        try {
            Yaml yaml = new Yaml();
            Object parsed = yaml.load(configContent);
            if (!(parsed instanceof Map)) {
                return ValidationResult.error("Configuration is not a valid YAML mapping");
            }
            root = (Map<String, Object>) parsed;
        } catch (Exception e) {
            return ValidationResult.error("YAML syntax error: " + e.getMessage());
        }

        // Check required: name
        if (root.get("name") == null || root.get("name").toString().isBlank()) {
            return ValidationResult.error("Missing required field: name");
        }

        // Check required: on (triggers) — handle YAML 1.1 boolean coercion
        // YAML 1.1 interprets "on" as Boolean.TRUE, so the key becomes Boolean
        // instead of String. Check both possibilities.
        Object on = root.get("on");
        if (on == null) {
            on = root.get(Boolean.TRUE);
        }
        if (on == null) {
            on = root.get("true");
        }
        if (on == null) {
            return ValidationResult.error("Missing required field: on (triggers)");
        }

        // Validate trigger details
        if (on instanceof Map) {
            Map<String, Object> triggerMap = (Map<String, Object>) on;
            ValidationResult triggerResult = validateTriggers(triggerMap);
            if (!triggerResult.valid()) {
                return triggerResult;
            }
        }

        // Check required: jobs
        Object jobs = root.get("jobs");
        if (jobs == null) {
            return ValidationResult.error("Missing required field: jobs");
        }
        if (!(jobs instanceof Map)) {
            return ValidationResult.error("'jobs' must be a mapping");
        }
        Map<String, Object> jobsMap = (Map<String, Object>) jobs;
        if (jobsMap.isEmpty()) {
            return ValidationResult.error("At least one job must be defined");
        }

        // Validate each job
        for (Map.Entry<String, Object> jobEntry : jobsMap.entrySet()) {
            String jobId = jobEntry.getKey();
            if (!(jobEntry.getValue() instanceof Map)) {
                return ValidationResult.error("Job '" + jobId + "' must be a mapping");
            }
            Map<String, Object> job = (Map<String, Object>) jobEntry.getValue();

            // Check runs-on
            if (job.get("runs-on") == null && job.get("runsOn") == null) {
                return ValidationResult.error("Missing required field: runs-on in job '" + jobId + "'");
            }

            // Check steps
            Object steps = job.get("steps");
            if (steps == null) {
                return ValidationResult.error("Missing required field: steps in job '" + jobId + "'");
            }
            if (!(steps instanceof List)) {
                return ValidationResult.error("'steps' in job '" + jobId + "' must be a list");
            }
            List<Object> stepsList = (List<Object>) steps;
            if (stepsList.isEmpty()) {
                return ValidationResult.error("Job '" + jobId + "' has an empty steps list");
            }

            // Validate each step
            Set<String> stepNames = new HashSet<>();
            for (int i = 0; i < stepsList.size(); i++) {
                if (!(stepsList.get(i) instanceof Map)) {
                    return ValidationResult.error("Step " + (i + 1) + " in job '" + jobId + "' must be a mapping");
                }
                Map<String, Object> step = (Map<String, Object>) stepsList.get(i);

                // Check step name exists
                Object stepName = step.get("name");
                if (stepName == null || stepName.toString().isBlank()) {
                    return ValidationResult.error("Step " + (i + 1) + " in job '" + jobId + "' is missing a name");
                }

                // Check duplicate step names
                String nameStr = stepName.toString();
                if (!stepNames.add(nameStr)) {
                    return ValidationResult.error("Duplicate step name '" + nameStr + "' in job '" + jobId + "'");
                }

                // Check action reference format
                Object uses = step.get("uses");
                if (uses != null && !uses.toString().isBlank()) {
                    String usesStr = uses.toString();
                    if (!ACTION_REF_PATTERN.matcher(usesStr).matches()) {
                        // Only warn for non-standard patterns — docker:// is valid too
                        if (!usesStr.startsWith("docker://") && !usesStr.startsWith("./")) {
                            return ValidationResult.error("Invalid action reference '" + usesStr + "' in step '"
                                    + nameStr + "': expected format 'owner/repo@ref'");
                        }
                    }
                }

                // Check step has at least uses or run
                Object run = step.get("run");
                if ((uses == null || uses.toString().isBlank())
                        && (run == null || run.toString().isBlank())) {
                    return ValidationResult.error(
                            "Step '" + nameStr + "' in job '" + jobId + "' must specify 'uses' or 'run'");
                }
            }
        }

        return ValidationResult.success();
    }

    @SuppressWarnings("unchecked")
    private ValidationResult validateTriggers(Map<String, Object> triggerMap) {
        // Validate push trigger branches
        Object push = triggerMap.get("push");
        if (push instanceof Map) {
            Map<String, Object> pushMap = (Map<String, Object>) push;
            Object branches = pushMap.get("branches");
            if (branches instanceof List && ((List<Object>) branches).isEmpty()) {
                return ValidationResult.error("Push trigger has empty branches list");
            }
        }

        // Validate pull_request trigger branches
        Object pr = triggerMap.get("pull_request");
        if (pr instanceof Map) {
            Map<String, Object> prMap = (Map<String, Object>) pr;
            Object branches = prMap.get("branches");
            if (branches instanceof List && ((List<Object>) branches).isEmpty()) {
                return ValidationResult.error("Pull request trigger has empty branches list");
            }
        }

        // Validate schedule cron expressions
        Object schedule = triggerMap.get("schedule");
        if (schedule instanceof List) {
            List<Object> scheduleList = (List<Object>) schedule;
            for (int i = 0; i < scheduleList.size(); i++) {
                if (scheduleList.get(i) instanceof Map) {
                    Map<String, Object> entry = (Map<String, Object>) scheduleList.get(i);
                    Object cron = entry.get("cron");
                    if (cron == null || cron.toString().isBlank()) {
                        return ValidationResult.error("Schedule entry " + (i + 1) + " missing 'cron' field");
                    }
                    if (!CRON_PATTERN.matcher(cron.toString().trim()).matches()) {
                        return ValidationResult.error(
                                "Invalid cron expression '" + cron + "' in schedule entry " + (i + 1));
                    }
                }
            }
        }

        return ValidationResult.success();
    }
}
