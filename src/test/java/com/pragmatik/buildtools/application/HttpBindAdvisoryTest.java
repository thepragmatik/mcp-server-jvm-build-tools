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
package com.pragmatik.buildtools.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link HttpBindAdvisory} (issue #199): loopback-bind
 * classification, and the gating of the startup warning itself — it must
 * fire only when tool authorization is disabled AND the bind is non-loopback.
 */
class HttpBindAdvisoryTest {

    private ListAppender<ILoggingEvent> logAppender;
    private Logger advisoryLogger;
    private Level originalLevel;

    @BeforeEach
    void attachLogCapture() {
        advisoryLogger = (Logger) LoggerFactory.getLogger(HttpBindAdvisory.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        advisoryLogger.addAppender(logAppender);
        originalLevel = advisoryLogger.getLevel();
        advisoryLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogCapture() {
        advisoryLogger.detachAppender(logAppender);
        advisoryLogger.setLevel(originalLevel);
    }

    private long securityWarnings() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("[security] HTTP transport"))
                .collect(Collectors.toList())
                .size();
    }

    @Test
    void warnsWhenAuthDisabledAndBindIsAllInterfaces() {
        new HttpBindAdvisory("", false).advise();
        assertThat(securityWarnings()).isEqualTo(1);
    }

    @Test
    void silentWhenAuthEnabledEvenOnNonLoopbackBind() {
        new HttpBindAdvisory("0.0.0.0", true).advise();
        assertThat(securityWarnings()).isZero();
    }

    @Test
    void silentWhenBoundToLoopbackEvenWithAuthDisabled() {
        new HttpBindAdvisory("127.0.0.1", false).advise();
        assertThat(securityWarnings()).isZero();
    }

    @Test
    void warnsWhenAuthDisabledAndAddressUnresolvable() {
        new HttpBindAdvisory("not-a-resolvable-host.invalid", false).advise();
        assertThat(securityWarnings()).isEqualTo(1);
    }

    @Test
    void unsetServerAddressIsNotLoopback() {
        assertThat(new HttpBindAdvisory("", true).isLoopbackBind()).isFalse();
    }

    @Test
    void ipv4LoopbackAddressIsLoopback() {
        assertThat(new HttpBindAdvisory("127.0.0.1", true).isLoopbackBind()).isTrue();
    }

    @Test
    void ipv6LoopbackAddressIsLoopback() {
        assertThat(new HttpBindAdvisory("::1", true).isLoopbackBind()).isTrue();
    }

    @Test
    void wildcardAddressIsNotLoopback() {
        assertThat(new HttpBindAdvisory("0.0.0.0", true).isLoopbackBind()).isFalse();
    }

    @Test
    void unresolvableAddressFailsSafeToNonLoopback() {
        assertThat(new HttpBindAdvisory("not-a-resolvable-host.invalid", true).isLoopbackBind())
                .isFalse();
    }
}
