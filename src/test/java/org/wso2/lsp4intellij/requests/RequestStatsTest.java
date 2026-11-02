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

import java.util.List;

public class RequestStatsTest {

    @Test
    public void summaryCountsErrorsAndTakesPercentilesOverEveryResponse() {
        RequestStats stats = new RequestStats();
        for (int millis = 1; millis <= 100; millis++) {
            stats.recordResponse("textDocument/hover", millis, millis % 10 == 0);
        }

        RequestStats.Summary summary = stats.summaries().get(0);

        Assert.assertEquals(new RequestStats.Summary("textDocument/hover", 100, 10, 50, 95, 100), summary);
    }

    @Test
    public void percentilesCoverOnlyTheMostRecentSamplesButMaxCoversAll() {
        RequestStats stats = new RequestStats();
        stats.recordResponse("textDocument/completion", 5_000, false);
        for (int i = 0; i < RequestStats.SAMPLES; i++) {
            stats.recordResponse("textDocument/completion", 10, false);
        }

        RequestStats.Summary summary = stats.summaries().get(0);

        Assert.assertEquals(RequestStats.SAMPLES + 1, summary.count());
        Assert.assertEquals(10, summary.medianMillis());
        Assert.assertEquals(10, summary.p95Millis());
        Assert.assertEquals(5_000, summary.maxMillis());
    }

    @Test
    public void summariesAreOrderedByMethod() {
        RequestStats stats = new RequestStats();
        stats.recordResponse("textDocument/hover", 1, false);
        stats.recordResponse("initialize", 1, false);
        stats.recordResponse("textDocument/completion", 1, false);

        List<String> methods = stats.summaries().stream().map(RequestStats.Summary::method).toList();

        Assert.assertEquals(List.of("initialize", "textDocument/completion", "textDocument/hover"), methods);
    }

    @Test
    public void percentileOfASingleSampleIsThatSample() {
        Assert.assertEquals(7, RequestStats.percentile(new long[]{7}, 50));
        Assert.assertEquals(7, RequestStats.percentile(new long[]{7}, 95));
    }
}
