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
import java.util.regex.Pattern;

/** Captures a few complete Maven compiler lines without retaining the intervening log. */
final class MavenDiagnosticOutput extends OutputStream {
    private static final int MAX_LINE_BYTES = 2_048;
    private static final int MAX_LINES = 13;
    private static final int MAX_ANSI_PREFIX_BYTES = 32;
    private static final byte[] PREFIX = "[ERROR] ".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern ANSI_SGR = Pattern.compile("\u001b\\[[0-9;:]*m");

    private final BoundedProcessOutput capture;
    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final List<String> diagnostics = new ArrayList<>(MAX_LINES);
    private int length;
    private int prefixLength;
    private int ansiPrefixBytes;
    private int ansiState;
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
            } else if (prefixLength < PREFIX.length) {
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
        if (prefixLength < PREFIX.length) {
            if (ansiState != 0 || (prefixLength == 0 && value == 0x1b)) {
                if (++ansiPrefixBytes > MAX_ANSI_PREFIX_BYTES) {
                    overflow = true;
                    return;
                }
                if (ansiState == 0) {
                    ansiState = 1; // ESC must be followed by '['.
                } else if (ansiState == 1 && value == '[') {
                    ansiState = 2;
                } else if (ansiState == 2 && value == 'm') {
                    ansiState = 0;
                } else if (ansiState != 2 || !((value >= '0' && value <= '9') || value == ';' || value == ':')) {
                    overflow = true;
                    return;
                }
            } else if (value == PREFIX[prefixLength]) {
                prefixLength++;
                candidate = true;
            } else {
                overflow = true; // An ordinary log line never needs buffering.
                return;
            }
        }
        if (length == line.length) {
            diagnosticsTruncated = true;
            overflow = true;
            return;
        }
        line[length++] = value;
    }

    private void finishLine() {
        if (candidate && !overflow) {
            int end = length > 0 && line[length - 1] == '\r' ? length - 1 : length;
            String text = ANSI_SGR.matcher(new String(line, 0, end, StandardCharsets.UTF_8))
                    .replaceAll("");
            if (MavenOutputParser.isCompilerDiagnosticLine(text) && !diagnostics.contains(text)) {
                if (diagnostics.size() == MAX_LINES) {
                    diagnosticsTruncated = true;
                } else {
                    diagnostics.add(text);
                }
            }
        }
        length = 0;
        prefixLength = 0;
        ansiPrefixBytes = 0;
        ansiState = 0;
        candidate = false;
        overflow = false;
    }
}
