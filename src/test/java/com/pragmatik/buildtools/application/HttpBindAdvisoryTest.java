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
package com.pragmatik.buildtools.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the loopback-bind classification of {@link HttpBindAdvisory}
 * (issue #199): unset address binds all interfaces (non-loopback), loopback
 * addresses classify as loopback, unresolvable addresses fail safe to
 * non-loopback.
 */
class HttpBindAdvisoryTest {

    @Test
    void unsetServerAddressIsNotLoopback() {
        HttpBindAdvisory advisory = new HttpBindAdvisory("", false);
        assertThat(advisory.isLoopbackBind()).isFalse();
    }

    @Test
    void loopbackAddressIsLoopback() {
        HttpBindAdvisory advisory = new HttpBindAdvisory("127.0.0.1", true);
        assertThat(advisory.isLoopbackBind()).isTrue();
    }

    @Test
    void wildcardNameIsNotLoopback() {
        HttpBindAdvisory advisory = new HttpBindAdvisory("0.0.0.0", true);
        assertThat(advisory.isLoopbackBind()).isFalse();
    }

    @Test
    void unresolvableAddressFailsSafeToNonLoopback() {
        HttpBindAdvisory advisory = new HttpBindAdvisory("not-a-resolvable-host.invalid", true);
        assertThat(advisory.isLoopbackBind()).isFalse();
    }
}
