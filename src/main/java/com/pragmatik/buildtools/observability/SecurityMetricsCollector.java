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

import com.pragmatik.buildtools.security.ToolAuthorizationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * {@link MeterBinder} that wraps {@link ToolAuthorizationService} counters as Micrometer gauges.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@code buildtools.auth.success.count} — total successful authorization checks</li>
 *   <li>{@code buildtools.auth.failure.count} — total failed authorization checks</li>
 * </ul>
 * These are read from the service's running counters, which are updated on every
 * authorization check call.
 */
@Component
public class SecurityMetricsCollector implements MeterBinder {

    private final ToolAuthorizationService authorizationService;

    public SecurityMetricsCollector(ToolAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        "buildtools.auth.success.count",
                        authorizationService,
                        ToolAuthorizationService::getAuthSuccessCount)
                .description("Total successful authorization checks")
                .register(registry);

        Gauge.builder(
                        "buildtools.auth.failure.count",
                        authorizationService,
                        ToolAuthorizationService::getAuthFailureCount)
                .description("Total failed authorization checks")
                .register(registry);
    }
}
