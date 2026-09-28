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
package com.pragmatik.buildtools.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.pragmatik.buildtools.build.BoundedProcessOutput;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MavenDiagnosticOutputTest {
    @Test
    void splitUtf8BytesFormOneCompleteDiagnostic() {
        MavenDiagnosticOutput output = new MavenDiagnosticOutput(new BoundedProcessOutput());
        byte[] bytes =
                "[ERROR] /synthetic/Sample.java:[42,1] cannot find symbol café\n".getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            output.write(value);
        }
        assertThat(output.diagnostics()).hasSize(1);
        assertThat(output.diagnostics().getFirst()).contains("café");
        assertThat(output.diagnosticsTruncated()).isFalse();
    }

    @Test
    void coloredCompilerLineIsRetainedWithoutControlSequences() {
        MavenDiagnosticOutput output = new MavenDiagnosticOutput(new BoundedProcessOutput());
        byte[] bytes = "\u001b[31m[ERROR] /synthetic/Sample.java:[42,1] cannot find symbol\u001b[0m\n"
                .getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            output.write(value);
        }
        assertThat(output.diagnostics()).containsExactly("[ERROR] /synthetic/Sample.java:[42,1] cannot find symbol");
        assertThat(output.diagnosticsTruncated()).isFalse();
    }

    @Test
    void duplicateAfterCapacityDoesNotClaimUniqueDiagnosticsWereDropped() {
        MavenDiagnosticOutput output = new MavenDiagnosticOutput(new BoundedProcessOutput());
        for (int i = 1; i <= 13; i++) {
            byte[] line = ("[ERROR] /synthetic/Sample.java:[" + i + ",1] cannot find symbol\n")
                    .getBytes(StandardCharsets.UTF_8);
            output.write(line, 0, line.length);
        }
        byte[] duplicate = "[ERROR] /synthetic/Sample.java:[1,1] cannot find symbol\n".getBytes(StandardCharsets.UTF_8);
        output.write(duplicate, 0, duplicate.length);

        assertThat(output.diagnostics()).hasSize(13);
        assertThat(output.diagnosticsTruncated()).isFalse();
    }

    @Test
    void hugeUnterminatedLineCannotBecomeAnUnboundedDiagnostic() {
        BoundedProcessOutput capture = new BoundedProcessOutput();
        MavenDiagnosticOutput output = new MavenDiagnosticOutput(capture);
        byte[] start = "[ERROR] /synthetic/Sample.java:[42,1] ".getBytes(StandardCharsets.UTF_8);
        output.write(start, 0, start.length);
        byte[] chunk = new byte[8192];
        java.util.Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 3_072; i++) {
            output.write(chunk, 0, chunk.length);
        }
        output.write('\n');
        assertThat(capture.snapshot().getBytes(StandardCharsets.UTF_8).length).isEqualTo(128 * 1024);
        assertThat(output.diagnostics()).isEmpty();
        assertThat(output.diagnosticsTruncated()).isTrue();
    }

    @Test
    void noisyTailDoesNotDisplaceFirstThirteenCompilerEvents() {
        MavenDiagnosticOutput output = new MavenDiagnosticOutput(new BoundedProcessOutput());
        for (int i = 1; i <= 14; i++) {
            byte[] line = ("[ERROR] /synthetic/Sample.java:[" + i + ",1] cannot find symbol\n")
                    .getBytes(StandardCharsets.UTF_8);
            output.write(line, 0, line.length);
        }
        for (int i = 0; i < 2_000; i++) {
            byte[] line = "[INFO] noisy tail\n".getBytes(StandardCharsets.UTF_8);
            output.write(line, 0, line.length);
        }
        assertThat(output.diagnostics()).hasSize(13);
        assertThat(output.diagnostics().getFirst()).contains(":[1,1]");
        assertThat(output.diagnosticsTruncated()).isTrue();
    }
}
