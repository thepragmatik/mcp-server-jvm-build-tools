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
package com.pragmatik.buildtools;

/**
 * An {@link AutoCloseable} handle for a trace-context activation or an in-flight
 * span. Closing the scope reverses the side effect that opened it (restoring the
 * previously-active span or clearing the inbound context), so callers can use it
 * safely in a try-with-resources block:
 *
 * <pre>{@code
 * try (TraceScope span = BuildTracer.startSpan("execute_build_command")) {
 *     // ... run the build; the span is the active context on this thread ...
 * } // span ends here and the previous context is restored
 * }</pre>
 *
 * <p>Unlike {@link AutoCloseable}, {@link #close()} declares no checked exception
 * so scopes compose cleanly with build logic that already throws unchecked
 * exceptions.
 */
public interface TraceScope extends AutoCloseable {

    /** A no-op scope, returned when there is nothing to activate. */
    TraceScope NOOP = () -> {};

    @Override
    void close();
}
