/*
 * Copyright 2025 Rahul Thakur
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.pragmatik.buildtools.dependency.security;

import java.util.OptionalDouble;

/** Scores the mandatory base metrics of a CVSS v3.1 vector using FIRST's equations. */
final class CvssV31 {
    private static final String PREFIX = "CVSS:3.1/";
    private static final int MAX_VECTOR_LENGTH = 128;

    private CvssV31() {}

    static OptionalDouble baseScore(String vector) {
        if (vector == null || vector.length() > MAX_VECTOR_LENGTH || !vector.startsWith(PREFIX)) {
            return OptionalDouble.empty();
        }
        // Deliberately accept base vectors only. Temporal/environmental scores need their own
        // equations; silently ignoring those metrics would mislabel a published score.
        String[] metrics = vector.substring(PREFIX.length()).split("/", -1);
        if (metrics.length != 8) return OptionalDouble.empty();
        String[] values = new String[8];
        for (String metric : metrics) {
            int separator = metric.indexOf(':');
            if (separator < 1 || separator != metric.lastIndexOf(':')) return OptionalDouble.empty();
            int index = metricIndex(metric.substring(0, separator));
            if (index < 0 || values[index] != null) return OptionalDouble.empty();
            values[index] = metric.substring(separator + 1);
        }
        for (String value : values) {
            if (value == null || value.length() != 1) return OptionalDouble.empty();
        }

        boolean changed = "C".equals(values[4]);
        if (!changed && !"U".equals(values[4])) return OptionalDouble.empty();
        double av = value(values[0], 0.85, 0.62, 0.55, 0.20, "N", "A", "L", "P");
        double ac = value(values[1], 0.77, 0.44, "L", "H");
        double pr = changed
                ? value(values[2], 0.85, 0.68, 0.50, "N", "L", "H")
                : value(values[2], 0.85, 0.62, 0.27, "N", "L", "H");
        double ui = value(values[3], 0.85, 0.62, "N", "R");
        double c = value(values[5], 0.56, 0.22, 0.0, "H", "L", "N");
        double i = value(values[6], 0.56, 0.22, 0.0, "H", "L", "N");
        double a = value(values[7], 0.56, 0.22, 0.0, "H", "L", "N");
        if (av < 0 || ac < 0 || pr < 0 || ui < 0 || c < 0 || i < 0 || a < 0) {
            return OptionalDouble.empty();
        }
        double iss = 1 - (1 - c) * (1 - i) * (1 - a);
        double impact = changed ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
        if (impact <= 0) return OptionalDouble.of(0.0);
        double exploitability = 8.22 * av * ac * pr * ui;
        double raw = changed ? 1.08 * (impact + exploitability) : impact + exploitability;
        return OptionalDouble.of(roundUp(Math.min(raw, 10.0)));
    }

    private static int metricIndex(String name) {
        return switch (name) {
            case "AV" -> 0;
            case "AC" -> 1;
            case "PR" -> 2;
            case "UI" -> 3;
            case "S" -> 4;
            case "C" -> 5;
            case "I" -> 6;
            case "A" -> 7;
            default -> -1;
        };
    }

    private static double value(String code, double first, double second, String a, String b) {
        if (a.equals(code)) return first;
        if (b.equals(code)) return second;
        return -1;
    }

    private static double value(String code, double first, double second, double third, String a, String b, String c) {
        if (a.equals(code)) return first;
        if (b.equals(code)) return second;
        if (c.equals(code)) return third;
        return -1;
    }

    private static double value(
            String code,
            double first,
            double second,
            double third,
            double fourth,
            String a,
            String b,
            String c,
            String d) {
        if (a.equals(code)) return first;
        if (b.equals(code)) return second;
        if (c.equals(code)) return third;
        if (d.equals(code)) return fourth;
        return -1;
    }

    private static double roundUp(double value) {
        // FIRST Appendix A: avoid promoting e.g. 0.1 + 0.2 to 0.4 on binary rounding noise.
        long scaled = Math.round(value * 100_000);
        long tenths = scaled / 10_000;
        if (scaled % 10_000 != 0) tenths++;
        return tenths / 10.0;
    }
}
