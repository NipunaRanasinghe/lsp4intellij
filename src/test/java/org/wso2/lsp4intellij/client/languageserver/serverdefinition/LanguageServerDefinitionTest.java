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
package org.wso2.lsp4intellij.client.languageserver.serverdefinition;

import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp4j.InitializeParams;
import org.junit.Assert;
import org.junit.Test;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link LanguageServerDefinition}: lifecycle management (start/stop),
 * language-id resolution, provider caching per working directory, customization hooks,
 * and the abstract base class contract.
 * 
 * Uses a test double ({@link FakeConnection}) to verify lifecycle without spawning real processes.
 */
public class LanguageServerDefinitionTest {

    /** Test double for StreamConnectionProvider that records lifecycle calls. */
    private static class FakeConnection implements StreamConnectionProvider {
        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger stopCount = new AtomicInteger();
        final InputStream in = new ByteArrayInputStream(new byte[0]);
        final OutputStream out = new ByteArrayOutputStream();

        @Override
        public void start() {
            startCount.incrementAndGet();
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
        }
    }

    /** Subclass exposing a controllable connection provider so we can test lifecycle without spawning a process. */
    private static class TestableDefinition extends LanguageServerDefinition {
        final Map<String, FakeConnection> created = new HashMap<>();

        TestableDefinition(String ext) {
            this.ext = ext;
        }

        @Override
        public StreamConnectionProvider createConnectionProvider(String workingDir) {
            FakeConnection c = new FakeConnection();
            created.put(workingDir, c);
            return c;
        }
    }

    /**
     * Verifies that {@link LanguageServerDefinition#SPLIT_CHAR} is a comma, used for parsing
     * comma-separated file extension lists.
     */
    @Test
    public void splitCharIsComma() {
        Assert.assertEquals(",", LanguageServerDefinition.SPLIT_CHAR);
    }

    /**
     * Verifies that {@link LanguageServerDefinition#languageIdFor(String)} falls back to
     * the extension itself when no language-id mapping is registered.
     */
    @Test
    public void languageIdForFallsBackToExtensionWhenMapEmpty() {
        TestableDefinition def = new TestableDefinition("go");
        Assert.assertEquals("go", def.languageIdFor("go"));
        Assert.assertEquals("rs", def.languageIdFor("rs"));
    }

    /**
     * Verifies that {@link LanguageServerDefinition#languageIdFor(String)} returns the registered
     * language ID when a mapping exists in the languageIds map.
     */
    @Test
    public void languageIdForUsesRegisteredMapping() {
        TestableDefinition def = new TestableDefinition("ts");
        Map<String, String> ids = new HashMap<>();
        ids.put("ts", "typescript");
        def.languageIds = ids;

        Assert.assertEquals("typescript", def.languageIdFor("ts"));
        Assert.assertEquals("unknown", def.languageIdFor("unknown"));
    }

    /**
     * Verifies that {@link LanguageServerDefinition#start(String)} creates a connection provider
     * and returns its input/output streams, calling start() on the provider.
     */
    @Test
    public void startCreatesConnectionAndReturnsStreams() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        Pair<InputStream, OutputStream> streams = def.start("/work");

        Assert.assertNotNull(streams);
        FakeConnection c = def.created.get("/work");
        Assert.assertNotNull(c);
        Assert.assertEquals(1, c.startCount.get());
        Assert.assertSame(c.in, streams.getLeft());
        Assert.assertSame(c.out, streams.getRight());
    }

    /**
     * Verifies that {@link LanguageServerDefinition#start(String)} reuses an existing provider
     * for the same working directory, calling start() only once on the provider.
     */
    @Test
    public void startReusesExistingProviderForSameWorkingDir() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        def.start("/work");
        def.start("/work");

        FakeConnection c = def.created.get("/work");
        Assert.assertEquals("provider should only be created and started once per workingDir",
                1, c.startCount.get());
    }

    /**
     * Verifies that {@link LanguageServerDefinition#start(String)} maintains separate connection
     * providers for different working directories.
     */
    @Test
    public void startKeepsSeparateProvidersPerWorkingDir() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        def.start("/work-a");
        def.start("/work-b");

        Assert.assertEquals(2, def.created.size());
        Assert.assertNotSame(def.created.get("/work-a"), def.created.get("/work-b"));
    }

    /**
     * Verifies that {@link LanguageServerDefinition#stop(String)} calls stop() on the
     * connection provider associated with the given working directory.
     */
    @Test
    public void stopInvokesProviderStop() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        def.start("/work");
        FakeConnection c = def.created.get("/work");

        def.stop("/work");
        Assert.assertEquals(1, c.stopCount.get());
    }

    /**
     * Verifies that calling {@link LanguageServerDefinition#stop(String)} on an unknown
     * working directory is a no-op: no exception is thrown and any existing providers are unaffected.
     */
    @Test
    public void stopOnUnknownWorkingDirIsNoOp() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        def.start("/work");
        FakeConnection c = def.created.get("/work");

        def.stop("/never-started");

        Assert.assertEquals("known provider must not be stopped", 0, c.stopCount.get());
    }

    /**
     * Verifies that stopping a provider and then restarting it for the same working directory
     * creates a fresh connection provider instance.
     */
    @Test
    public void stopFollowedByStartCreatesFreshProvider() throws IOException {
        TestableDefinition def = new TestableDefinition("go");
        def.start("/work");
        FakeConnection first = def.created.get("/work");
        def.stop("/work");

        def.start("/work");
        FakeConnection second = def.created.get("/work");

        Assert.assertNotSame(first, second);
        Assert.assertEquals(1, second.startCount.get());
    }

    /**
     * Verifies that {@link LanguageServerDefinition#customizeInitializeParams(InitializeParams)}
     * is a no-op by default, leaving InitializeParams unchanged.
     */
    @Test
    public void customizeInitializeParamsIsNoOpByDefault() {
        TestableDefinition def = new TestableDefinition("go");
        InitializeParams params = new InitializeParams();
        params.setProcessId(42);
        params.setLocale("en-US");

        def.customizeInitializeParams(params);
        Assert.assertEquals(Integer.valueOf(42), params.getProcessId());
        Assert.assertEquals("en-US", params.getLocale());
    }

    /**
     * Verifies that {@link LanguageServerDefinition#getInitializationOptions(com.intellij.openapi.project.Project)}
     * returns null by default, allowing subclasses to override for custom initialization options.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void getInitializationOptionsReturnsNullByDefault() {
        TestableDefinition def = new TestableDefinition("go");
        Assert.assertNull(def.getInitializationOptions(null));
    }

    /**
     * Verifies that {@link LanguageServerDefinition#getServerListener()} returns the default
     * {@link ServerListener#DEFAULT} implementation.
     */
    @Test
    public void getServerListenerReturnsDefault() {
        TestableDefinition def = new TestableDefinition("go");
        Assert.assertSame(ServerListener.DEFAULT, def.getServerListener());
    }

    /**
     * Verifies that the toString() representation of a {@link LanguageServerDefinition}
     * includes the file extension.
     */
    @Test
    public void toStringIncludesExt() {
        TestableDefinition def = new TestableDefinition("go");
        Assert.assertTrue(def.toString().contains("go"));
    }

    /**
     * Verifies that the base {@link LanguageServerDefinition#createConnectionProvider(String)}
     * throws {@link UnsupportedOperationException}, enforcing that subclasses must override it.
     */
    @Test
    public void baseCreateConnectionProviderThrows() {
        LanguageServerDefinition raw = new LanguageServerDefinition();
        Assert.assertThrows(UnsupportedOperationException.class,
                () -> raw.createConnectionProvider("/work"));
    }

    /**
     * Verifies that {@link LanguageServerDefinition#getDisplayName()} falls back to the extension,
     * rendering a multi-extension definition as a comma separated list.
     */
    @Test
    public void displayNameDefaultsToExt() {
        Assert.assertEquals("go", new TestableDefinition("go").getDisplayName());
        Assert.assertEquals("bal, balx", new TestableDefinition("bal,balx").getDisplayName());
    }

    /**
     * Verifies that a definition with no extension still yields a usable label rather than null or
     * an empty string, since the UI has no other name to show.
     */
    @Test
    public void displayNameFallsBackWhenExtMissing() {
        Assert.assertEquals("Language server", new TestableDefinition("").getDisplayName());
        Assert.assertEquals("Language server", new TestableDefinition(null).getDisplayName());
    }

    /**
     * Verifies that {@link LanguageServerDefinition#withDisplayName(String)} overrides the
     * extension-derived default and returns the same definition, so it can be chained at the point
     * of registration.
     */
    @Test
    public void withDisplayNameOverridesDefaultAndChains() {
        TestableDefinition def = new TestableDefinition("bal");
        Assert.assertSame(def, def.withDisplayName("Ballerina"));
        Assert.assertEquals("Ballerina", def.getDisplayName());
    }

    /**
     * Verifies that clearing the name restores the extension-derived default.
     */
    @Test
    public void withDisplayNameClearedRestoresDefault() {
        TestableDefinition def = new TestableDefinition("bal");
        def.withDisplayName("Ballerina");
        def.withDisplayName(null);
        Assert.assertEquals("bal", def.getDisplayName());
        def.withDisplayName("");
        Assert.assertEquals("bal", def.getDisplayName());
    }
}
