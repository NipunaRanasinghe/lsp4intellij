/*
 * Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.lsp4intellij.requests;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-server success/failure counters for each {@link Timeouts} kind.
 *
 * <p>One instance lives on each {@code LanguageServerWrapper}, so the counters exist regardless of
 * which UI (if any) a consumer plugin registers - previously this state lived only inside the status
 * bar widget, so a consumer that did not register that widget silently lost every counter.
 *
 * <p>{@link #recordResult} is called from request paths, not the EDT, and possibly by more than one thread
 * at once for the same {@link Timeouts} kind. Both maps are populated once for every {@link Timeouts}
 * constant at construction time, so a call to {@link #recordResult} never races on map structure - only the
 * per-kind {@link AtomicInteger} is mutated concurrently.
 */
public final class TimeoutStats {

    private final Map<Timeouts, AtomicInteger> successCounts = new ConcurrentHashMap<>();
    private final Map<Timeouts, AtomicInteger> failureCounts = new ConcurrentHashMap<>();

    public TimeoutStats() {
        for (Timeouts timeout : Timeouts.values()) {
            successCounts.put(timeout, new AtomicInteger());
            failureCounts.put(timeout, new AtomicInteger());
        }
    }

    /**
     * Records the outcome of one request of the given kind.
     */
    public void recordResult(Timeouts timeout, boolean success) {
        (success ? successCounts : failureCounts).get(timeout).incrementAndGet();
    }

    public int getSuccessCount(Timeouts timeout) {
        return successCounts.get(timeout).get();
    }

    public int getFailureCount(Timeouts timeout) {
        return failureCounts.get(timeout).get();
    }
}
