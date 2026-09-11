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
package com.pragmatik.buildtools.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.aop.framework.ProxyFactory;

/**
 * Unit-level regression test for issue #189: {@link ToolCatalogueSummary} must resolve service
 * groups through Spring AOP proxies. In production {@code ToolMetricsAspect} CGLIB-proxies every
 * tool service bean; scanning the proxy class previously yielded no {@code @Tool} annotations,
 * collapsing all tools into the single {@code ungrouped} bucket.
 */
@DisplayName("ToolCatalogueSummary groups through AOP proxies (issue #189)")
class ToolCatalogueSummaryProxyTest {

    static class FakeToolsA {
        @Tool(name = "alpha_one", description = "fake")
        public String alphaOne() {
            return "ok";
        }

        @Tool(name = "alpha_two", description = "fake")
        public String alphaTwo() {
            return "ok";
        }
    }

    static class FakeToolsB {
        @Tool(name = "beta_tool", description = "fake")
        public String betaTool() {
            return "ok";
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void cglibProxiedToolBeansStillGroupByService() {
        Object proxiedA = new ProxyFactory(new FakeToolsA()).getProxy();
        Object proxiedB = new ProxyFactory(new FakeToolsB()).getProxy();

        DeterministicToolCallbackProvider provider = new DeterministicToolCallbackProvider(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                        .toolObjects(new FakeToolsA(), new FakeToolsB())
                        .build());
        ToolCatalogueSummary summary = new ToolCatalogueSummary(provider, List.of(proxiedA, proxiedB));

        Map<String, Object> result = summary.summary();
        Map<String, List<String>> groups = (Map<String, List<String>>) result.get("groups");

        assertThat(groups)
                .as("proxied tool beans must group by their user class, not fall back to 'ungrouped'")
                .containsOnlyKeys("fakeToolsA", "fakeToolsB");
        assertThat(groups.get("fakeToolsA")).containsExactly("alpha_one", "alpha_two");
        assertThat(groups.get("fakeToolsB")).containsExactly("beta_tool");
    }

    @SuppressWarnings("unchecked")
    @Test
    void repeatedSummaryCallsAreIdenticalWithProxiedBeans() {
        Object proxiedA = new ProxyFactory(new FakeToolsA()).getProxy();
        Object proxiedB = new ProxyFactory(new FakeToolsB()).getProxy();

        DeterministicToolCallbackProvider provider = new DeterministicToolCallbackProvider(
                org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                        .toolObjects(new FakeToolsA(), new FakeToolsB())
                        .build());
        ToolCatalogueSummary summary = new ToolCatalogueSummary(provider, List.of(proxiedA, proxiedB));

        Map<String, Object> first = new java.util.LinkedHashMap<>(summary.summary());
        Map<String, Object> second = new java.util.LinkedHashMap<>(summary.summary());

        assertThat(second).isEqualTo(first);
    }
}
