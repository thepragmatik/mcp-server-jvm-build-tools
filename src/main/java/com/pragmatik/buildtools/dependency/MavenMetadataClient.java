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
package com.pragmatik.buildtools.dependency;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;

/** A fixed-host, bounded transport for Maven Central's metadata.xml endpoint. */
final class MavenMetadataClient {
    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final URI MAVEN_CENTRAL = URI.create("https://repo1.maven.org/maven2/");
    private static final HttpClient DEFAULT_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final Pattern GROUP = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9][A-Za-z0-9_-]*)*");
    private static final Pattern ARTIFACT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private final URI base;
    private final HttpClient client;
    private final Duration requestTimeout;

    MavenMetadataClient() {
        this(MAVEN_CENTRAL, DEFAULT_HTTP_CLIENT);
    }

    MavenMetadataClient(URI base, HttpClient client) {
        this(base, client, REQUEST_TIMEOUT);
    }

    MavenMetadataClient(URI base, HttpClient client, Duration requestTimeout) {
        this.base = Objects.requireNonNull(base);
        this.client = Objects.requireNonNull(client);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
    }

    static boolean validCoordinates(String groupId, String artifactId) {
        return groupId != null
                && groupId.length() <= 256
                && GROUP.matcher(groupId).matches()
                && artifactId != null
                && artifactId.length() <= 128
                && ARTIFACT.matcher(artifactId).matches()
                && !artifactId.equals(".")
                && !artifactId.equals("..");
    }

    Response fetch(String groupId, String artifactId) throws IOException, InterruptedException {
        if (!validCoordinates(groupId, artifactId)) {
            throw new IllegalArgumentException("Invalid Maven coordinates");
        }
        URI uri = base.resolve(groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml");
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(requestTimeout).GET().build();
        HttpResponse<byte[]> response = client.send(request, ignored -> new BoundedSubscriber(MAX_RESPONSE_BYTES));
        return new Response(response.statusCode(), response.body());
    }

    record Response(int statusCode, byte[] body) {}

    /** Rejects excessive response bytes before aggregating them in the JDK subscriber. */
    private static final class BoundedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int maxBytes;
        private long received;
        private Flow.Subscription subscription;

        private BoundedSubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                received += item.remaining();
                if (received > maxBytes) {
                    subscription.cancel();
                    delegate.onError(new IOException("Maven metadata response exceeds limit"));
                    return;
                }
            }
            delegate.onNext(items);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
