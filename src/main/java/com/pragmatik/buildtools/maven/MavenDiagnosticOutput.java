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
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Captures bounded Maven compiler lines and test totals without retaining the intervening log. */
final class MavenDiagnosticOutput extends OutputStream {
    private static final int MAX_LINE_BYTES = 2_048;
    private static final int MAX_LINES = 13;
    private static final int MAX_ANSI_PREFIX_BYTES = 32;
    private static final byte[][] PREFIXES = {
        "[ERROR] ".getBytes(StandardCharsets.US_ASCII),
        "[INFO] Tests run:".getBytes(StandardCharsets.US_ASCII),
        "[ERROR] Tests run:".getBytes(StandardCharsets.US_ASCII)
    };
    private static final Pattern ANSI_SGR = Pattern.compile("\u001b\\[[0-9;:]*m");

    private final BoundedProcessOutput capture;
    private final byte[] line = new byte[MAX_LINE_BYTES];
    private final List<Candidate> diagnostics = new ArrayList<>(MAX_LINES);
    private final List<Candidate> testSummaries = new ArrayList<>(MAX_LINES);
    private long position;
    private int length;
    private int prefixLength;
    private int possiblePrefixes = (1 << PREFIXES.length) - 1;
    private int ansiPrefixBytes;
    private int ansiState;
    private boolean candidate;
    private boolean overflow;
    private boolean diagnosticsTruncated;

    private record Candidate(String text, long startByte, long endByte) {}

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
                int start = i;
                while (i < end && source[i] != '\n') {
                    i++;
                }
                position += i - start;
                if (i < end) {
                    position++;
                    finishLine();
                    i++;
                }
            } else if (!candidate) {
                accept(source[i++]);
            } else {
                int start = i;
                while (i < end && source[i] != '\n') {
                    i++;
                }
                int segment = i - start;
                position += segment;
                if (segment > line.length - length) {
                    diagnosticsTruncated = true;
                    overflow = true;
                } else {
                    System.arraycopy(source, start, line, length, segment);
                    length += segment;
                }
                if (i < end) {
                    position++;
                    finishLine();
                    i++;
                }
            }
        }
    }

    synchronized List<String> diagnostics() {
        return orderedCandidates().stream().map(Candidate::text).toList();
    }

    synchronized String snapshotForAnalysis(String visibleOutput, Set<String> replayedCompilerLines) {
        if (!capture.truncated()) {
            return capture.snapshot();
        }
        BoundedProcessOutput.TruncatedParts parts = capture.truncatedParts();
        StringBuilder middle = new StringBuilder();
        for (Candidate candidate : orderedCandidates()) {
            if (candidate.endByte() <= parts.headEndByte() || candidate.startByte() >= parts.tailStartByte()) {
                continue;
            }
            String text = candidate.text();
            if (MavenOutputParser.isTestSummaryLine(text)
                    || (!visibleOutput.contains(text) && replayedCompilerLines.add(text))) {
                middle.append(text).append('\n');
            }
        }
        return parts.head() + "\n" + middle + "\n" + parts.tail();
    }

    private List<Candidate> orderedCandidates() {
        List<Candidate> retained = new ArrayList<>(diagnostics.size() + testSummaries.size());
        retained.addAll(diagnostics);
        retained.addAll(testSummaries);
        retained.sort(Comparator.comparingLong(Candidate::startByte));
        return retained;
    }

    synchronized boolean diagnosticsTruncated() {
        return diagnosticsTruncated;
    }

    private void accept(byte value) {
        position++;
        if (value == '\n') {
            finishLine();
            return;
        }
        if (overflow) {
            return;
        }
        if (!candidate) {
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
            } else if (!advanceCandidatePrefix(value)) {
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
            Candidate retained = new Candidate(text, position - length - 1, position);
            if (MavenOutputParser.isCompilerDiagnosticLine(text)
                    && diagnostics.stream().noneMatch(d -> d.text().equals(text))) {
                if (diagnostics.size() == MAX_LINES) {
                    diagnosticsTruncated = true;
                } else {
                    diagnostics.add(retained);
                }
            } else if (MavenOutputParser.isTestSummaryLine(text)) {
                if (testSummaries.size() == MAX_LINES) {
                    testSummaries.remove(0);
                    diagnosticsTruncated = true;
                }
                testSummaries.add(retained);
            }
        }
        length = 0;
        prefixLength = 0;
        possiblePrefixes = (1 << PREFIXES.length) - 1;
        ansiPrefixBytes = 0;
        ansiState = 0;
        candidate = false;
        overflow = false;
    }

    private boolean advanceCandidatePrefix(byte value) {
        int next = 0;
        for (int i = 0; i < PREFIXES.length; i++) {
            byte[] prefix = PREFIXES[i];
            int bit = 1 << i;
            if ((possiblePrefixes & bit) != 0 && prefixLength < prefix.length && prefix[prefixLength] == value) {
                next |= bit;
            }
        }
        if (next == 0) {
            return false;
        }
        possiblePrefixes = next;
        prefixLength++;
        for (int i = 0; i < PREFIXES.length; i++) {
            if ((next & (1 << i)) != 0 && prefixLength == PREFIXES[i].length) {
                candidate = true;
                break;
            }
        }
        return true;
    }
}
