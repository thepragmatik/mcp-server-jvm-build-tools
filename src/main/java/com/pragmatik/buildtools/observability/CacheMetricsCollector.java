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

import com.pragmatik.buildtools.build.BuildCacheService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * {@link MeterBinder} that wraps {@link BuildCacheService} cache-health scoring as Micrometer
 * gauges.
 *
 * <p>Registers per-tool gauges for:
 * <ul>
 *   <li>{@code buildtools.cache.hit.rate} — last reported cache hit rate per tool</li>
 *   <li>{@code buildtools.cache.score} — overall cache health score (0-100) per tool</li>
 * </ul>
 */
@Component
public class CacheMetricsCollector implements MeterBinder {

    private final BuildCacheService buildCacheService;

    public CacheMetricsCollector(BuildCacheService buildCacheService) {
        this.buildCacheService = buildCacheService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (String tool : new String[] {"maven", "gradle", "sbt"}) {
            Gauge.builder("buildtools.cache.score", buildCacheService, svc -> svc.getLastScore(tool))
                    .tag("tool", tool)
                    .tag("category", "overall")
                    .description("Cache health score (0-100)")
                    .register(registry);

            Gauge.builder("buildtools.cache.hit.rate", buildCacheService, svc -> svc.getLastHitRate(tool))
                    .tag("tool", tool)
                    .description("Last reported build cache hit rate (0.0–1.0)")
                    .register(registry);
        }
    }
}
