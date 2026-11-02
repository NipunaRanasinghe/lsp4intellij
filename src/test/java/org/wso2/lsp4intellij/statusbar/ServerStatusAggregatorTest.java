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
package org.wso2.lsp4intellij.statusbar;

import org.junit.Assert;
import org.junit.Test;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;

import java.util.EnumSet;
import java.util.List;

/**
 * Unit tests for {@link ServerStatusAggregator}. Plain JUnit, no IntelliJ Platform test fixture needed
 * since the class under test has no Swing or platform dependency.
 */
public class ServerStatusAggregatorTest {

    @Test
    public void testEmptyCollectionAggregatesToStopped() {
        Assert.assertEquals(ServerStatus.STOPPED, ServerStatusAggregator.aggregate(List.of()));
    }

    @Test
    public void testSingleStatusAggregatesToItself() {
        for (ServerStatus status : ServerStatus.values()) {
            Assert.assertEquals(status, ServerStatusAggregator.aggregate(List.of(status)));
        }
    }

    @Test
    public void testStoppedWinsOverEveryOtherStatus() {
        Assert.assertEquals(ServerStatus.STOPPED, ServerStatusAggregator.aggregate(
                EnumSet.of(ServerStatus.STOPPED, ServerStatus.INITIALIZED, ServerStatus.STARTED)));
    }

    @Test
    public void testStoppingBeatsStartingStartedAndInitialized() {
        Assert.assertEquals(ServerStatus.STOPPING, ServerStatusAggregator.aggregate(
                EnumSet.of(ServerStatus.STOPPING, ServerStatus.STARTING, ServerStatus.STARTED,
                        ServerStatus.INITIALIZED)));
    }

    @Test
    public void testStartingBeatsStartedAndInitialized() {
        Assert.assertEquals(ServerStatus.STARTING, ServerStatusAggregator.aggregate(
                EnumSet.of(ServerStatus.STARTING, ServerStatus.STARTED, ServerStatus.INITIALIZED)));
    }

    @Test
    public void testStartedBeatsInitialized() {
        Assert.assertEquals(ServerStatus.STARTED, ServerStatusAggregator.aggregate(
                EnumSet.of(ServerStatus.STARTED, ServerStatus.INITIALIZED)));
    }

    @Test
    public void testAllInitializedAggregatesToInitialized() {
        Assert.assertEquals(ServerStatus.INITIALIZED, ServerStatusAggregator.aggregate(
                List.of(ServerStatus.INITIALIZED, ServerStatus.INITIALIZED)));
    }
}
