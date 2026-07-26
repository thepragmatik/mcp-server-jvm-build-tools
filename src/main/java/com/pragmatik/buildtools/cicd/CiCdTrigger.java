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

/**
 * Trigger configuration for a CI/CD pipeline.
 *
 * @param push              Push trigger configuration (branches, paths)
 * @param pullRequest       Pull request trigger configuration (branches)
 * @param release           Release trigger configuration (types)
 * @param schedule          Schedule trigger configurations (cron expressions)
 * @param workflowDispatch  Whether manual workflow dispatch is enabled
 */
public record CiCdTrigger(
        CiCdPushTrigger push,
        CiCdPrTrigger pullRequest,
        CiCdReleaseTrigger release,
        List<CiCdScheduleEntry> schedule,
        boolean workflowDispatch) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private CiCdPushTrigger push;
        private CiCdPrTrigger pullRequest;
        private CiCdReleaseTrigger release;
        private List<CiCdScheduleEntry> schedule = List.of();
        private boolean workflowDispatch;

        public Builder push(CiCdPushTrigger push) {
            this.push = push;
            return this;
        }

        public Builder pullRequest(CiCdPrTrigger pullRequest) {
            this.pullRequest = pullRequest;
            return this;
        }

        public Builder release(CiCdReleaseTrigger release) {
            this.release = release;
            return this;
        }

        public Builder schedule(List<CiCdScheduleEntry> schedule) {
            this.schedule = schedule;
            return this;
        }

        public Builder workflowDispatch(boolean workflowDispatch) {
            this.workflowDispatch = workflowDispatch;
            return this;
        }

        public CiCdTrigger build() {
            return new CiCdTrigger(push, pullRequest, release, schedule, workflowDispatch);
        }
    }
}
