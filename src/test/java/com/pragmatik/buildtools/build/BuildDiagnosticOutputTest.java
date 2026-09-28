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

class BuildDiagnosticOutputTest {
    @Test
    void splitUtf8AndAnsiArePreservedOnlyAsCompleteLines() throws Exception {
        BuildDiagnosticOutput output = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        byte[] bytes = "\u001b[31m[error] /synthetic/Sample.scala:42: cannot find symbol café\u001b[0m\n"
                .getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            output.write(value);
        }
        assertThat(output.diagnostics()).containsExactly("[error] /synthetic/Sample.scala:42: cannot find symbol café");
        assertThat(output.diagnosticsTruncated()).isFalse();
    }

    @Test
    void uniqueLimitAndOversizedLineSignalOmissions() throws Exception {
        BuildDiagnosticOutput output = new BuildDiagnosticOutput(new BoundedProcessOutput(), "gradle");
        for (int i = 1; i <= 13; i++) {
            byte[] line = ("error: cannot find symbol number " + i + "\n").getBytes(StandardCharsets.UTF_8);
            output.write(line, 0, line.length);
        }
        output.write("error: cannot find symbol number 1\n".getBytes(StandardCharsets.UTF_8));
        assertThat(output.diagnostics()).hasSize(13);
        assertThat(output.diagnosticsTruncated()).isFalse();

        output.write("error: cannot find symbol number 14\n".getBytes(StandardCharsets.UTF_8));
        assertThat(output.diagnostics()).hasSize(13);
        assertThat(output.diagnosticsTruncated()).isTrue();

        BuildDiagnosticOutput oversized = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        oversized.write(("[error] " + "x".repeat(3_000) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(oversized.diagnostics()).isEmpty();
        assertThat(oversized.diagnosticsTruncated()).isTrue();
    }

    @Test
    void boundaryLineBetweenHeadAndTailSurvivesWithoutRetainingLog() throws Exception {
        BoundedProcessOutput capture = new BoundedProcessOutput();
        BuildDiagnosticOutput output = new BuildDiagnosticOutput(capture, "gradle");
        byte[] head = ("x".repeat(BoundedProcessOutput.HEAD_BYTES - 1) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = "y".repeat(BoundedProcessOutput.TAIL_BYTES).getBytes(StandardCharsets.UTF_8);
        output.write(head);
        output.write("error: cannot find symbol\n".getBytes(StandardCharsets.UTF_8));
        output.write(tail);
        assertThat(capture.snapshot()).doesNotContain("cannot find symbol");
        assertThat(output.diagnostics()).containsExactly("error: cannot find symbol");
        assertThat(capture.truncated()).isTrue();
    }

    @Test
    void exactlyTwoKilobyteLineFitsButNextByteSignalsOverflow() throws Exception {
        String prefix = "[error] ";
        BuildDiagnosticOutput exact = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        exact.write((prefix + "x".repeat(2_048 - prefix.length()) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(exact.diagnostics()).hasSize(1);
        assertThat(exact.diagnosticsTruncated()).isFalse();

        BuildDiagnosticOutput oversized = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        oversized.write((prefix + "x".repeat(2_049 - prefix.length()) + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(oversized.diagnostics()).isEmpty();
        assertThat(oversized.diagnosticsTruncated()).isTrue();
    }

    @Test
    void eofCompletesOnlyBoundedPartialLine() throws Exception {
        BuildDiagnosticOutput output = new BuildDiagnosticOutput(new BoundedProcessOutput(), "gradle");
        output.write("error: cannot find symbol".getBytes(StandardCharsets.UTF_8));
        assertThat(output.diagnostics()).isEmpty();
        output.finishAtEof();
        output.finishAtEof();
        assertThat(output.diagnostics()).containsExactly("error: cannot find symbol");
        assertThat(output.diagnosticsTruncated()).isFalse();

        BuildDiagnosticOutput oversized = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        oversized.write(("[error] " + "x".repeat(2_049)).getBytes(StandardCharsets.UTF_8));
        oversized.finishAtEof();
        assertThat(oversized.diagnostics()).isEmpty();
        assertThat(oversized.diagnosticsTruncated()).isTrue();
    }

    @Test
    void ordinarySbtWarningsCannotDisplaceError() throws Exception {
        BuildDiagnosticOutput output = new BuildDiagnosticOutput(new BoundedProcessOutput(), "sbt");
        for (int i = 0; i < 100; i++) {
            output.write(("[warn] warning " + i + "\n").getBytes(StandardCharsets.UTF_8));
        }
        output.write("[error] cannot find symbol\n".getBytes(StandardCharsets.UTF_8));
        assertThat(output.diagnostics()).containsExactly("[error] cannot find symbol");
        assertThat(output.diagnosticsTruncated()).isFalse();
    }
}
