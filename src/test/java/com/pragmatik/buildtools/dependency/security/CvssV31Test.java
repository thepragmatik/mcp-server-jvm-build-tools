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
package com.pragmatik.buildtools.dependency.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CvssV31Test {

    @ParameterizedTest
    @MethodSource("officialExamples")
    void scoresFirstPublishedBaseVectors(String vector, double score) {
        assertThat(CvssV31.baseScore(vector)).hasValue(score);
    }

    private static Stream<Arguments> officialExamples() {
        // FIRST CVSS v3.1 examples and user guide, including both Scope branches.
        return Stream.of(
                Arguments.of("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H", 9.8),
                Arguments.of("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H", 10.0),
                Arguments.of("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H", 7.5),
                Arguments.of("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:L/I:L/A:N", 7.2),
                Arguments.of("CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:H/I:N/A:N", 5.3));
    }

    @Test
    void acceptsNonCanonicalMetricOrderAndScoresZeroImpact() {
        assertThat(CvssV31.baseScore("CVSS:3.1/S:U/A:H/I:N/C:N/UI:N/PR:N/AC:L/AV:N"))
                .hasValue(7.5);
        assertThat(CvssV31.baseScore("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N"))
                .hasValue(0.0);
    }

    @ParameterizedTest
    @MethodSource("unsupportedVectors")
    void rejectsUnsupportedOrMalformedVectors(String vector) {
        assertThat(CvssV31.baseScore(vector)).isEmpty();
    }

    private static Stream<String> unsupportedVectors() {
        String base = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H";
        return Stream.of(
                "9.8",
                base.replace("3.1", "3.0"),
                base.replace("3.1", "4.0"),
                base.replace("/A:H", ""),
                base + "/AV:P",
                base + "/E:F",
                base.replace("AV:N", "AV:Z"),
                base.replace("AV:N", "AV:n"),
                base + "/",
                " " + base,
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H/UNKNOWN:X");
    }
}
