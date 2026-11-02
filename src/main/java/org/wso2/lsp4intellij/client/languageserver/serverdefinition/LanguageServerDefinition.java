/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import com.intellij.openapi.diagnostic.Logger;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp4j.InitializeParams;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.client.connection.StreamConnectionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A trait representing a ServerDefinition.
 */
public class LanguageServerDefinition {

    private static final Logger LOG = Logger.getInstance(LanguageServerDefinition.class);

    public String ext;
    private String displayName;
    protected Map<String, String> languageIds = Collections.emptyMap();
    private final Map<String, StreamConnectionProvider> streamConnectionProviders = new ConcurrentHashMap<>();
    public static final String SPLIT_CHAR = ",";

    /**
     * Starts a Language server for the given directory and returns a tuple (InputStream, OutputStream).
     *
     * @param workingDir The root directory
     * @return The input and output streams of the server
     * @throws IOException if the stream connection provider is crashed
     */
    public Pair<InputStream, OutputStream> start(String workingDir) throws IOException {
        StreamConnectionProvider streamConnectionProvider = streamConnectionProviders.get(workingDir);
        if (streamConnectionProvider != null) {
            return new ImmutablePair<>(streamConnectionProvider.getInputStream(),
                    streamConnectionProvider.getOutputStream());
        } else {
            streamConnectionProvider = createConnectionProvider(workingDir);
            streamConnectionProvider.start();
            streamConnectionProviders.put(workingDir, streamConnectionProvider);
            return new ImmutablePair<>(streamConnectionProvider.getInputStream(),
                    streamConnectionProvider.getOutputStream());
        }
    }

    /**
     * Stops the Language server corresponding to the given working directory.
     *
     * @param workingDir The root directory
     */
    public void stop(String workingDir) {
        StreamConnectionProvider streamConnectionProvider = streamConnectionProviders.get(workingDir);
        if (streamConnectionProvider != null) {
            streamConnectionProvider.stop();
            streamConnectionProviders.remove(workingDir);
        } else {
            LOG.warn("No connection for workingDir " + workingDir + " and ext " + ext);
        }
    }

    /**
     * Returns the initialization options for the given uri.
     *
     * @param uri file URI
     * @return initialization options
     * @deprecated use {@link #customizeInitializeParams(InitializeParams)} instead
     */
    @Deprecated
    public Object getInitializationOptions(URI uri) {
        return null;
    }

    /**
     * Use this method to modify the {@link InitializeParams} that was initialized by this library. The values
     * assigned to the passed {@link InitializeParams} after this method ends will be the ones sent to the LSP server.
     *
     * @param params the parameters with some prefilled values.
     */
    public void customizeInitializeParams(InitializeParams params) {
    }

    @Override
    public String toString() {
        return "ServerDefinition for " + ext;
    }

    /**
     * A short name for this server, for UI that has one line to spend on it - a tree node, a menu
     * entry, a status bar popup. {@link #toString()} is not usable there: subclasses render it as a
     * full command line, so {@code RawCommandServerDefinition} produces the entire executable path
     * plus arguments.
     *
     * <p>Defaults to the file extensions this definition serves, for example {@code bal}, or
     * {@code bal, balx} for a definition covering several. Set a real product name with
     * {@link #withDisplayName(String)}, or override this method in a subclass.
     */
    public String getDisplayName() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return ext == null || ext.isEmpty() ? "Language server" : ext.replace(SPLIT_CHAR, ", ");
    }

    /**
     * Sets the name {@link #getDisplayName()} returns, and returns this definition so it can be named
     * where it is registered:
     *
     * <pre>{@code
     * IntellijLanguageClient.addServerDefinition(
     *         new RawCommandServerDefinition("bal", command).withDisplayName("Ballerina"));
     * }</pre>
     *
     * <p>Passing null or an empty string restores the default derived from {@link #ext}.
     *
     * @param displayName the name to show in the server tree, status bar popup and other UI
     * @return this definition
     */
    public LanguageServerDefinition withDisplayName(@Nullable String displayName) {
        this.displayName = displayName;
        return this;
    }

    /**
     * Creates a StreamConnectionProvider given the working directory.
     *
     * @param workingDir The root directory
     * @return The stream connection provider
     */
    public StreamConnectionProvider createConnectionProvider(String workingDir) {
        throw new UnsupportedOperationException();
    }

    public ServerListener getServerListener() {
        return ServerListener.DEFAULT;
    }

    /**
     * Return language id for the given extension. if there is no langauge ids registered then the
     * return value will be the value of <code>extension</code>.
     */
    public String languageIdFor(String extension) {
        return languageIds.getOrDefault(extension, extension);
    }
}
