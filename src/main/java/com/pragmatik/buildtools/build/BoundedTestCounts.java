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

/** Saturating arithmetic for untrusted test-summary counters. */
public final class BoundedTestCounts {
    private boolean capped;

    /** Parses a regex-validated decimal without overflow or allocation. */
    public int parse(String digits) {
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            if (value > (BuildResultLimits.MAX_VISIBLE_COUNTER - digit) / 10) {
                capped = true;
                return BuildResultLimits.MAX_VISIBLE_COUNTER;
            }
            value = value * 10 + digit;
        }
        return value;
    }

    public int add(int left, int right) {
        if (left > BuildResultLimits.MAX_VISIBLE_COUNTER - right) {
            capped = true;
            return BuildResultLimits.MAX_VISIBLE_COUNTER;
        }
        return left + right;
    }

    public int atLeast(int actual, int minimum) {
        if (actual < minimum) {
            capped = true;
            return minimum;
        }
        return actual;
    }

    public void markInconsistent() {
        capped = true;
    }

    public boolean wasCapped() {
        return capped;
    }
}
