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
package com.pragmatik.buildtools.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedProcessOutputTest {

    @Test
    void retainsFirstAndLastBytesOfLargeUnterminatedLine() {
        BoundedProcessOutput output = new BoundedProcessOutput();
        String source = "BEGIN" + "x".repeat(512 * 1024) + "END";
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);

        output.write(bytes, 0, bytes.length);

        assertThat(output.truncated()).isTrue();
        assertThat(output.snapshot()).startsWith("BEGIN").endsWith("END");
        assertThat(output.headSnapshot()).startsWith("BEGIN").hasSize(BoundedProcessOutput.HEAD_BYTES);
        assertThat(output.tailSnapshot()).endsWith("END").hasSize(BoundedProcessOutput.TAIL_BYTES);
        assertThat(output.snapshot().getBytes(StandardCharsets.UTF_8).length)
                .isEqualTo(BoundedProcessOutput.HEAD_BYTES + BoundedProcessOutput.TAIL_BYTES);
    }

    @Test
    void preservesShortOutputWithoutClaimingTruncation() {
        BoundedProcessOutput output = new BoundedProcessOutput();
        output.appendLine("first");
        output.appendLine("last");

        assertThat(output.truncated()).isFalse();
        assertThat(output.snapshot()).isEqualTo("first" + System.lineSeparator() + "last" + System.lineSeparator());
    }
}
