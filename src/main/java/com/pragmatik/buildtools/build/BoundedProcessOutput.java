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

/** Bounded, thread-safe capture of the beginning and end of one process stream. */
public final class BoundedProcessOutput extends OutputStream {

    public static final int HEAD_BYTES = 32 * 1024;
    public static final int TAIL_BYTES = 96 * 1024;

    private final byte[] head = new byte[HEAD_BYTES];
    private final byte[] tail = new byte[TAIL_BYTES];
    private int headSize;
    private int tailSize;
    private int tailNext;
    private long bytesSeen;

    @Override
    public synchronized void write(int value) {
        byte one = (byte) value;
        if (headSize < head.length) {
            head[headSize++] = one;
        } else {
            tail[tailNext] = one;
            tailNext = (tailNext + 1) % tail.length;
            if (tailSize < tail.length) {
                tailSize++;
            }
        }
        bytesSeen++;
    }

    @Override
    public synchronized void write(byte[] source, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, source.length);
        int headLength = Math.min(length, head.length - headSize);
        System.arraycopy(source, offset, head, headSize, headLength);
        headSize += headLength;
        int cursor = offset + headLength;
        int remaining = length - headLength;
        while (remaining > 0) {
            int chunk = Math.min(remaining, tail.length - tailNext);
            System.arraycopy(source, cursor, tail, tailNext, chunk);
            tailNext = (tailNext + chunk) % tail.length;
            tailSize = Math.min(tail.length, tailSize + chunk);
            cursor += chunk;
            remaining -= chunk;
        }
        bytesSeen += length;
    }

    public synchronized void appendLine(String line) {
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    public synchronized void append(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    public synchronized boolean truncated() {
        return bytesSeen > HEAD_BYTES + TAIL_BYTES;
    }

    /** The captured leading bytes, decoded separately when a middle gap exists. */
    public synchronized String headSnapshot() {
        return new String(head, 0, headSize, StandardCharsets.UTF_8);
    }

    /** The captured trailing bytes, decoded separately when a middle gap exists. */
    public synchronized String tailSnapshot() {
        byte[] bytes = new byte[tailSize];
        if (tailSize > 0) {
            int start = tailSize == tail.length ? tailNext : 0;
            int first = Math.min(tailSize, tail.length - start);
            System.arraycopy(tail, start, bytes, 0, first);
            System.arraycopy(tail, 0, bytes, first, tailSize - first);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Complete captured lines around a discarded middle, with original byte offsets. */
    public record TruncatedParts(String head, String tail, long headEndByte, long tailStartByte) {}

    public synchronized TruncatedParts truncatedParts() {
        if (!truncated()) {
            throw new IllegalStateException("Output has no discarded middle");
        }
        int headEnd = 0;
        for (int i = 0; i < headSize; i++) {
            if (head[i] == '\n') {
                headEnd = i + 1;
            }
        }
        byte[] tailBytes = new byte[tailSize];
        int start = tailNext;
        int first = Math.min(tailSize, tail.length - start);
        System.arraycopy(tail, start, tailBytes, 0, first);
        System.arraycopy(tail, 0, tailBytes, first, tailSize - first);
        int tailStart = tailSize;
        for (int i = 0; i < tailSize; i++) {
            if (tailBytes[i] == '\n') {
                tailStart = i + 1;
                break;
            }
        }
        return new TruncatedParts(
                new String(head, 0, headEnd, StandardCharsets.UTF_8),
                new String(tailBytes, tailStart, tailSize - tailStart, StandardCharsets.UTF_8),
                headEnd,
                bytesSeen - tailSize + tailStart);
    }

    public synchronized String snapshot() {
        byte[] bytes = new byte[headSize + tailSize];
        System.arraycopy(head, 0, bytes, 0, headSize);
        if (tailSize > 0) {
            int start = tailSize == tail.length ? tailNext : 0;
            int first = Math.min(tailSize, tail.length - start);
            System.arraycopy(tail, start, bytes, headSize, first);
            System.arraycopy(tail, 0, bytes, headSize + first, tailSize - first);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return snapshot();
    }
}
