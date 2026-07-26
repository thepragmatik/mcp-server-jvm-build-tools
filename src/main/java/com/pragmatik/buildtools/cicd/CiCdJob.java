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
 * CI job definition.
 *
 * @param name             Display name of the job
 * @param id               Job identifier for the target format
 * @param runsOn           Runner type (e.g., "ubuntu-latest")
 * @param needs            Job dependencies
 * @param strategy         Matrix strategy configuration
 * @param steps            List of steps in the job
 * @param env              Environment variables for the job
 * @param timeoutMinutes   Job timeout in minutes
 * @param condition        Job condition expression
 */
public record CiCdJob(
        String name,
        String id,
        String runsOn,
        List<String> needs,
        CiCdMatrixStrategy strategy,
        List<CiCdStep> steps,
        Map<String, String> env,
        int timeoutMinutes,
        String condition) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private String id;
        private String runsOn = "ubuntu-latest";
        private List<String> needs = List.of();
        private CiCdMatrixStrategy strategy;
        private List<CiCdStep> steps = List.of();
        private Map<String, String> env = Map.of();
        private int timeoutMinutes = 60;
        private String condition;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder runsOn(String runsOn) {
            this.runsOn = runsOn;
            return this;
        }

        public Builder needs(List<String> needs) {
            this.needs = needs;
            return this;
        }

        public Builder strategy(CiCdMatrixStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        public Builder steps(List<CiCdStep> steps) {
            this.steps = steps;
            return this;
        }

        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }

        public Builder timeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
            return this;
        }

        public Builder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public CiCdJob build() {
            return new CiCdJob(name, id, runsOn, needs, strategy, steps, env, timeoutMinutes, condition);
        }
    }
}
