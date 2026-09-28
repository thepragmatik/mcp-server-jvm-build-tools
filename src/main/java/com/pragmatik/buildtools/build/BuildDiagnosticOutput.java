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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Captures a few complete Gradle or sbt failure lines while the ordinary
 * head/tail capture drains the entire stream. Only the privacy policy may
 * project these raw lines to an MCP response.
 */
final class BuildDiagnosticOutput extends OutputStream {
    private static final int MAX_LINE_BYTES = 2_048;
    private static final int MAX_LINES = 13;
    private static final Pattern ANSI_SGR = Pattern.compile("\u001b\\[[0-9;:]*m");

    private final BoundedProcessOutput capture;
    private final String tool;
    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final List<String> diagnostics = new ArrayList<>(MAX_LINES);
    private int length;
    private boolean overflow;
    private boolean diagnosticsTruncated;

    BuildDiagnosticOutput(BoundedProcessOutput capture, String tool) {
        this.capture = Objects.requireNonNull(capture);
        if (!"gradle".equals(tool) && !"sbt".equals(tool)) {
            throw new IllegalArgumentException("Unsupported diagnostic stream");
        }
        this.tool = tool;
    }

    @Override
    public synchronized void write(int value) {
        capture.write(value);
        accept((byte) value);
    }

    @Override
    public synchronized void write(byte[] source, int offset, int count) {
        Objects.checkFromIndexSize(offset, count, source.length);
        capture.write(source, offset, count);
        int end = offset + count;
        for (int cursor = offset; cursor < end; ) {
            int newline = cursor;
            while (newline < end && source[newline] != '\n') {
                newline++;
            }
            if (!overflow) {
                int segment = newline - cursor;
                if (segment > line.length - length) {
                    overflow = true;
                    diagnosticsTruncated = true;
                } else {
                    System.arraycopy(source, cursor, line, length, segment);
                    length += segment;
                }
            }
            if (newline == end) {
                break;
            }
            finishLine();
            cursor = newline + 1;
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
        } else if (!overflow) {
            if (length == line.length) {
                overflow = true;
                diagnosticsTruncated = true;
            } else {
                line[length++] = value;
            }
        }
    }

    private void finishLine() {
        if (!overflow && candidate()) {
            int end = length > 0 && line[length - 1] == '\r' ? length - 1 : length;
            String text = ANSI_SGR.matcher(new String(line, 0, end, StandardCharsets.UTF_8))
                    .replaceAll("");
            if (!diagnostics.contains(text)) {
                if (diagnostics.size() == MAX_LINES) {
                    diagnosticsTruncated = true;
                } else {
                    diagnostics.add(text);
                }
            }
        }
        length = 0;
        overflow = false;
    }

    private boolean candidate() {
        if ("sbt".equals(tool)) {
            return contains("[error]") && !contains("[error] total time:");
        }
        return contains("error:")
                || startsWith("e:")
                || startsWith("execution failed for task")
                || (startsWith("> task ") && contains(" failed"))
                || contains("() failed");
    }

    private boolean startsWith(String prefix) {
        int start = 0;
        while (start < length && (line[start] == ' ' || line[start] == '\t')) {
            start++;
        }
        if (length - start < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            int value = line[start + i] & 0xff;
            if (value >= 'A' && value <= 'Z') {
                value += 'a' - 'A';
            }
            if (value != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(String needle) {
        int last = length - needle.length();
        for (int i = 0; i <= last; i++) {
            int j = 0;
            for (; j < needle.length(); j++) {
                int value = line[i + j] & 0xff;
                if (value >= 'A' && value <= 'Z') {
                    value += 'a' - 'A';
                }
                if (value != needle.charAt(j)) {
                    break;
                }
            }
            if (j == needle.length()) {
                return true;
            }
        }
        return false;
    }
}
