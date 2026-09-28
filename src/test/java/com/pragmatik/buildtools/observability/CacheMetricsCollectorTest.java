package com.pragmatik.buildtools.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pragmatik.buildtools.build.BuildCacheService;
import com.pragmatik.buildtools.build.BuildToolProvider;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheMetricsCollectorTest {

    @Test
    void exposesConfigurationScoresWithoutAnUnmeasuredHitRate(@TempDir Path project) throws IOException {
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, "<project><artifactId>maven-build-cache-extension</artifactId></project>");

        BuildCacheService service = new BuildCacheService(new BuildToolProvider());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            new CacheMetricsCollector(service).bindTo(registry);

            for (String tool : new String[] {"maven", "gradle", "sbt"}) {
                Gauge score = registry.find("buildtools.cache.score")
                        .tags("tool", tool, "category", "overall")
                        .gauge();
                assertNotNull(score);
                assertEquals(0.0, score.value());
            }

            service.analyzeCacheHealth(project.toString(), "maven");
            assertEquals(
                    30.0,
                    registry.find("buildtools.cache.score")
                            .tags("tool", "maven", "category", "overall")
                            .gauge()
                            .value());

            Files.writeString(pom, "<project/>");
            service.analyzeCacheHealth(project.toString(), "maven");
            assertEquals(
                    0.0,
                    registry.find("buildtools.cache.score")
                            .tags("tool", "maven", "category", "overall")
                            .gauge()
                            .value());
            assertTrue(registry.find("buildtools.cache.hit.rate").meters().isEmpty());
        } finally {
            registry.close();
        }
    }
}
