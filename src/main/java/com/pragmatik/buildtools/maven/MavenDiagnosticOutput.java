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

import com.pragmatik.buildtools.build.BoundedProcessOutput;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Captures a few complete Maven compiler lines without retaining the intervening log. */
final class MavenDiagnosticOutput extends OutputStream {
    private static final int MAX_LINE_BYTES = 2_048;
    private static final int MAX_LINES = 13;
    private static final byte[] PREFIX = "[ERROR] ".getBytes(StandardCharsets.US_ASCII);

    private final BoundedProcessOutput capture;
    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final List<String> diagnostics = new ArrayList<>(MAX_LINES);
    private int length;
    private boolean candidate;
    private boolean overflow;
    private boolean diagnosticsTruncated;

    MavenDiagnosticOutput(BoundedProcessOutput capture) {
        this.capture = capture;
    }

    @Override
    public synchronized void write(int value) {
        capture.write(value);
        accept((byte) value);
    }

    @Override
    public synchronized void write(byte[] source, int offset, int count) {
        capture.write(source, offset, count);
        int end = offset + count;
        for (int i = offset; i < end; ) {
            if (overflow) {
                while (i < end && source[i] != '\n') {
                    i++;
                }
                if (i < end) {
                    finishLine();
                    i++;
                }
            } else if (length < PREFIX.length) {
                accept(source[i++]);
            } else {
                int start = i;
                while (i < end && source[i] != '\n') {
                    i++;
                }
                int segment = i - start;
                if (segment > line.length - length) {
                    diagnosticsTruncated = true;
                    overflow = true;
                } else {
                    System.arraycopy(source, start, line, length, segment);
                    length += segment;
                }
                if (i < end) {
                    finishLine();
                    i++;
                }
            }
        }
    }

    synchronized List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    synchronized boolean diagnosticsTruncated() {
        return diagnosticsTruncated;
    }

    private void accept(byte value) {
        if (value == '\n') {
            finishLine();
            return;
        }
        if (overflow) {
            return;
        }
        if (length < PREFIX.length && value != PREFIX[length]) {
            overflow = true; // An ordinary log line never needs buffering.
            return;
        }
        candidate = true;
        if (length == line.length) {
            diagnosticsTruncated = true;
            overflow = true;
            return;
        }
        line[length++] = value;
    }

    private void finishLine() {
        if (candidate && !overflow) {
            if (diagnostics.size() == MAX_LINES) {
                diagnosticsTruncated = true;
            } else {
                int end = length > 0 && line[length - 1] == '\r' ? length - 1 : length;
                String text = new String(line, 0, end, StandardCharsets.UTF_8);
                if (MavenOutputParser.isCompilerDiagnosticLine(text) && !diagnostics.contains(text)) {
                    diagnostics.add(text);
                }
            }
        }
        length = 0;
        candidate = false;
        overflow = false;
    }
}
