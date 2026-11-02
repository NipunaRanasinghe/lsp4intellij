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

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-server response times for each JSON-RPC method, in both directions.
 *
 * <p>Fed by {@code TracingMessageConsumer} for every response it matches to a request, whether or not
 * tracing is on. The count, error count and maximum cover every response since the wrapper was
 * created. The median and 95th percentile cover the last {@link #SAMPLES} responses of each method,
 * so the memory held per method is fixed.
 */
public final class RequestStats {

    /**
     * How many of the most recent response times each method keeps for its median and 95th percentile.
     */
    public static final int SAMPLES = 1_000;

    private final Map<String, MethodStats> byMethod = new ConcurrentHashMap<>();

    /**
     * Records one response to a request of the given method.
     *
     * @param millis time between the request and its response
     * @param error  whether the response carried an error
     */
    public void recordResponse(@NotNull String method, long millis, boolean error) {
        byMethod.computeIfAbsent(method, m -> new MethodStats()).recordResponse(millis, error);
    }

    /**
     * Returns one summary per method that has had a response, ordered by method name.
     */
    @NotNull
    public List<Summary> summaries() {
        List<Summary> summaries = new ArrayList<>();
        byMethod.forEach((method, stats) -> summaries.add(stats.summarize(method)));
        summaries.sort(Comparator.comparing(Summary::method));
        return summaries;
    }

    /**
     * Returns the nearest-rank percentile of an ascending, non-empty array.
     */
    static long percentile(long[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }

    /**
     * Response times for one method.
     *
     * @param method       the JSON-RPC method
     * @param count        responses since the wrapper was created
     * @param errors       error responses since the wrapper was created
     * @param medianMillis median of the last {@link #SAMPLES} response times
     * @param p95Millis    95th percentile of the last {@link #SAMPLES} response times
     * @param maxMillis    slowest response since the wrapper was created
     */
    public record Summary(@NotNull String method, int count, int errors, long medianMillis, long p95Millis,
                          long maxMillis) {
    }

    private static final class MethodStats {

        private final long[] samples = new long[SAMPLES];
        private int next;
        private int sampled;
        private int count;
        private int errors;
        private long max;

        synchronized void recordResponse(long millis, boolean error) {
            samples[next] = millis;
            next = (next + 1) % SAMPLES;
            sampled = Math.min(sampled + 1, SAMPLES);
            count++;
            if (error) {
                errors++;
            }
            max = Math.max(max, millis);
        }

        synchronized Summary summarize(String method) {
            long[] sorted = Arrays.copyOf(samples, sampled);
            Arrays.sort(sorted);
            return new Summary(method, count, errors, percentile(sorted, 50), percentile(sorted, 95), max);
        }
    }
}
