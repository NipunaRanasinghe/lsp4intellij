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

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link TimeoutStats}. Plain JUnit, no IntelliJ Platform test fixture needed since the
 * class under test has no Swing or platform dependency - it is the state that used to live only inside
 * {@code LSPServerStatusWidget} and is now on {@code LanguageServerWrapper} instead.
 */
public class TimeoutStatsTest {

    @Test
    public void testStartsAtZeroForEveryKind() {
        TimeoutStats stats = new TimeoutStats();

        for (Timeouts timeout : Timeouts.values()) {
            Assert.assertEquals(0, stats.getSuccessCount(timeout));
            Assert.assertEquals(0, stats.getFailureCount(timeout));
        }
    }

    @Test
    public void testRecordCountsSuccessesAndFailuresSeparately() {
        TimeoutStats stats = new TimeoutStats();

        stats.recordResult(Timeouts.HOVER, true);
        stats.recordResult(Timeouts.HOVER, true);
        stats.recordResult(Timeouts.HOVER, false);

        Assert.assertEquals(2, stats.getSuccessCount(Timeouts.HOVER));
        Assert.assertEquals(1, stats.getFailureCount(Timeouts.HOVER));
    }

    @Test
    public void testCountersAreIsolatedPerTimeoutKind() {
        TimeoutStats stats = new TimeoutStats();

        stats.recordResult(Timeouts.COMPLETION, true);
        stats.recordResult(Timeouts.DEFINITION, false);

        Assert.assertEquals(1, stats.getSuccessCount(Timeouts.COMPLETION));
        Assert.assertEquals(0, stats.getFailureCount(Timeouts.COMPLETION));
        Assert.assertEquals(0, stats.getSuccessCount(Timeouts.DEFINITION));
        Assert.assertEquals(1, stats.getFailureCount(Timeouts.DEFINITION));
    }

    /**
     * {@code record} is called from request paths, not the EDT, and possibly by more than one thread
     * at once for the same {@link Timeouts} kind - exercises that directly rather than trusting
     * {@link java.util.concurrent.atomic.AtomicInteger} by inspection alone.
     */
    @Test
    public void testRecordIsSafeUnderConcurrentUpdates() throws InterruptedException {
        TimeoutStats stats = new TimeoutStats();
        int threadCount = 16;
        int recordsPerThread = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            boolean success = i % 2 == 0;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int r = 0; r < recordsPerThread; r++) {
                        stats.recordResult(Timeouts.COMPLETION, success);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        Assert.assertTrue("threads did not finish in time", done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        int expectedPerOutcome = (threadCount / 2) * recordsPerThread;
        Assert.assertEquals(expectedPerOutcome, stats.getSuccessCount(Timeouts.COMPLETION));
        Assert.assertEquals(expectedPerOutcome, stats.getFailureCount(Timeouts.COMPLETION));
    }
}
