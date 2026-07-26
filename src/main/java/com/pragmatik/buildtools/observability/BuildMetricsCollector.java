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
package com.pragmatik.buildtools.observability;

import com.pragmatik.buildtools.build.BuildPerformanceService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * {@link MeterBinder} that wraps {@link BuildPerformanceService} data as Micrometer gauges.
 *
 * <p>Registers the following metrics:
 * <ul>
 *   <li>{@code buildtools.build.count} — total number of profiled builds executed</li>
 *   <li>{@code buildtools.build.duration.last} — duration (seconds) of the most recent profiled build</li>
 * </ul>
 */
@Component
public class BuildMetricsCollector implements MeterBinder {

    private final BuildPerformanceService buildPerformanceService;

    public BuildMetricsCollector(BuildPerformanceService buildPerformanceService) {
        this.buildPerformanceService = buildPerformanceService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("buildtools.build.count", buildPerformanceService, BuildPerformanceService::getBuildCount)
                .description("Total number of profiled builds executed")
                .register(registry);

        Gauge.builder(
                        "buildtools.build.duration.last",
                        buildPerformanceService,
                        BuildPerformanceService::getLastDurationSeconds)
                .description("Duration of the most recent profiled build (seconds)")
                .register(registry);
    }
}
