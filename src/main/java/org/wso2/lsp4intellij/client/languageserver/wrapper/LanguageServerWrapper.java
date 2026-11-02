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
package org.wso2.lsp4intellij.client.languageserver.wrapper;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.remoteServer.util.CloudNotifier;
import com.intellij.util.PlatformIcons;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DocumentHighlightCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.FoldingRangeCapabilities;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeKindSupportCapabilities;
import org.eclipse.lsp4j.FoldingRangeSupportCapabilities;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.OnTypeFormattingCapabilities;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.DefaultLanguageClient;
import org.wso2.lsp4intellij.client.ServerWrapperBaseClientContext;
import org.wso2.lsp4intellij.client.languageserver.ServerOptions;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.DefaultRequestManager;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.extensions.LSPExtensionManager;
import org.wso2.lsp4intellij.listeners.DocumentListenerImpl;
import org.wso2.lsp4intellij.listeners.EditorMouseListenerImpl;
import org.wso2.lsp4intellij.listeners.EditorMouseMotionListenerImpl;
import org.wso2.lsp4intellij.listeners.LSPCaretListenerImpl;
import org.wso2.lsp4intellij.logging.LspLogSource;
import org.wso2.lsp4intellij.logging.LspLogStore;
import org.wso2.lsp4intellij.logging.LspTraceLevel;
import org.wso2.lsp4intellij.logging.TracingMessageConsumer;
import org.wso2.lsp4intellij.requests.RequestExecutor;
import org.wso2.lsp4intellij.requests.RequestStats;
import org.wso2.lsp4intellij.requests.TimeoutStats;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.services.LspServerManager;
import org.wso2.lsp4intellij.utils.FileUtils;
import org.wso2.lsp4intellij.utils.LSPException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.INITIALIZED;
import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.STARTED;
import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.STARTING;
import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.STOPPED;
import static org.wso2.lsp4intellij.client.languageserver.ServerStatus.STOPPING;
import static org.wso2.lsp4intellij.requests.Timeout.getTimeout;
import static org.wso2.lsp4intellij.requests.Timeouts.INIT;
import static org.wso2.lsp4intellij.requests.Timeouts.SHUTDOWN;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.invokeLater;
import static org.wso2.lsp4intellij.utils.FileUtils.editorToURIString;
import static org.wso2.lsp4intellij.utils.FileUtils.reloadEditors;
import static org.wso2.lsp4intellij.utils.FileUtils.sanitizeURI;

/**
 * The implementation of a LanguageServerWrapper (specific to a serverDefinition and a project).
 */
public class LanguageServerWrapper {

    public LanguageServerDefinition serverDefinition;
    private final LSPExtensionManager extManager;
    private final Project project;
    // These collections are mutated from the EDT, the background pool, and lsp4j threads.
    private final Set<Editor> toConnect = ConcurrentHashMap.newKeySet();
    private final String projectRootPath;
    private final Set<String> urisUnderLspControl = ConcurrentHashMap.newKeySet();
    private final Set<Editor> connectedEditors = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<EditorEventManager>> uriToEditorManagers = new ConcurrentHashMap<>();
    private LanguageServer languageServer;
    private LanguageClient client;
    private RequestManager requestManager;
    // Set on the launcher thread, read by the LSP Inspector on the EDT.
    private volatile InitializeResult initializeResult;
    private Future<?> launcherFuture;
    private ExecutorService launcherExecutor;
    private final ExecutorService dispatcher;
    private final RequestExecutor requestExecutor = new RequestExecutor(this);
    private CompletableFuture<InitializeResult> initializeFuture;
    private boolean capabilitiesAlreadyRequested = false;
    private final AtomicInteger crashCount = new AtomicInteger(0);
    private volatile boolean alreadyShownTimeout = false;
    private volatile boolean alreadyShownCrash = false;
    private volatile ServerStatus status = STOPPED;
    private volatile LspTraceLevel traceLevel = LspTraceLevel.OFF;
    private final TimeoutStats timeoutStats = new TimeoutStats();
    private final RequestStats requestStats = new RequestStats();
    // Requests sent or received by the current server process and not yet answered. Replaced on each
    // start, so a request the previous process never answered does not stay listed.
    private volatile Map<String, TracingMessageConsumer.InFlightRequest> inFlightRequests = new ConcurrentHashMap<>();
    private static final Logger LOG = Logger.getInstance(LanguageServerWrapper.class);
    private static final CloudNotifier notifier = new CloudNotifier("Language Server Protocol client");

    private static final List<String> codeActionResolveProperties = new ArrayList<>(List.of("edit"));

    public LanguageServerWrapper(@NotNull LanguageServerDefinition serverDefinition, @NotNull Project project) {
        this(serverDefinition, project, null);
    }

    public LanguageServerWrapper(@NotNull LanguageServerDefinition serverDefinition, @NotNull Project project,
                                 @Nullable LSPExtensionManager extManager) {
        this.serverDefinition = serverDefinition;
        this.project = project;
        // We need to keep the project rootPath in addition to the project instance, since we cannot get the project
        // base path if the project is disposed.
        this.projectRootPath = project.getBasePath();
        this.extManager = extManager;
        this.dispatcher = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "lsp4intellij-" + serverDefinition.ext);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Submits a task to this wrapper's dispatcher thread. Tasks of one server are executed in submission
     * order, but do not block tasks of other servers. Tasks submitted after {@link #dispose()} are dropped.
     */
    public void pool(Runnable task) {
        try {
            dispatcher.submit(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.debug("Task submitted after wrapper disposal was dropped", e);
        }
    }

    /**
     * @return The request executor which enforces the timeout/crash policy for blocking request waits
     */
    public RequestExecutor getRequestExecutor() {
        return requestExecutor;
    }

    /**
     * @param uri     A file uri
     * @param project The related project
     * @return The wrapper for the given uri, or None
     */
    public static LanguageServerWrapper forUri(String uri, Project project) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        return manager != null ? manager.wrapperForUri(uri) : null;
    }

    public static LanguageServerWrapper forVirtualFile(VirtualFile file, Project project) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        return manager != null ? manager.wrapperForUri(FileUtils.vfsToUri(file)) : null;
    }

    /**
     * @param editor An editor
     * @return The wrapper for the given editor, or None
     */
    public static LanguageServerWrapper forEditor(Editor editor) {
        Project project = editor.getProject();
        if (project == null) {
            return null;
        }
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        if (manager == null) {
            return null;
        }
        LanguageServerWrapper wrapper = manager.wrapperForUri(editorToURIString(editor));
        if (wrapper != null) {
            return wrapper;
        }
        // Fallback for when the editor's URI drifted (file moved/renamed) after it was connected.
        for (LanguageServerWrapper candidate : manager.allWrappers()) {
            if (candidate.connectedEditors.contains(editor)) {
                return candidate;
            }
        }
        return null;
    }

    public static LanguageServerWrapper forProject(Project project) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        return manager != null ? manager.lastWrapper() : null;
    }

    public LanguageServerDefinition getServerDefinition() {
        return serverDefinition;
    }

    public String getProjectRootPath() {
        return projectRootPath;
    }

    /**
     * @return if the server supports willSaveWaitUntil
     */
    public boolean isWillSaveWaitUntil() {
        return Optional.ofNullable(getServerCapabilities())
                .map(ServerCapabilities::getTextDocumentSync)
                .map(Either::getRight)
                .map(TextDocumentSyncOptions::getWillSaveWaitUntil)
                .orElse(false);
    }

    /**
     * Warning: this is a long running operation.
     *
     * @return the languageServer capabilities, or null if initialization job didn't complete
     */
    @Nullable
    public ServerCapabilities getServerCapabilities() {
        if (initializeResult != null) {
            return initializeResult.getCapabilities();
        } else {
            try {
                start();
                if (initializeFuture != null) {
                    initializeFuture.get((capabilitiesAlreadyRequested ? 0 : getTimeout(INIT)), TimeUnit.MILLISECONDS);
                    notifySuccess(INIT);
                }
                capabilitiesAlreadyRequested = true;
            } catch (TimeoutException e) {
                notifyFailure(INIT);
                String msg = String.format("%s \n is not initialized after %d seconds",
                        serverDefinition.toString(), getTimeout(INIT) / 1000);
                LOG.warn(msg, e);
                invokeLater(() -> {
                    if (!alreadyShownTimeout) {
                        notifier.showMessage(msg, MessageType.WARNING);
                        alreadyShownTimeout = true;
                    }
                });
                stop(false);
            } catch (Exception e) {
                LOG.warn(e);
                stop(false);
            }
        }

        return initializeResult != null ? initializeResult.getCapabilities() : null;
    }

    public void notifyResult(Timeouts timeouts, boolean success) {
        timeoutStats.recordResult(timeouts, success);
    }

    /**
     * Returns this wrapper's timeout success/failure counters, read by the LSP Inspector's Details
     * tab. See {@link #notifyResult(Timeouts, boolean)} for how they are updated.
     */
    public TimeoutStats getTimeoutStats() {
        return timeoutStats;
    }

    /**
     * Returns this wrapper's per-method response times, read by the LSP Inspector's Requests tab.
     */
    @NotNull
    public RequestStats getRequestStats() {
        return requestStats;
    }

    /**
     * Returns the requests the current server process has sent or received and not yet answered,
     * oldest first. Requests left unanswered by a process that stopped stay listed until the next start.
     */
    @NotNull
    public List<TracingMessageConsumer.InFlightRequest> getPendingRequests() {
        List<TracingMessageConsumer.InFlightRequest> pending = new ArrayList<>(inFlightRequests.values());
        pending.sort(Comparator.comparingLong(TracingMessageConsumer.InFlightRequest::startNanos));
        return pending;
    }

    public void notifySuccess(Timeouts timeouts) {
        notifyResult(timeouts, true);
    }

    public void notifyFailure(Timeouts timeouts) {
        notifyResult(timeouts, false);
    }

    /**
     * Returns the EditorEventManager for a given uri
     * <p>
     * WARNING: actually a file can be present in multiple editors, this function just gives you one editor.
     * use {@link #getEditorManagersFor(String)} instead
     * only use for document level events such as open, close, ...
     *
     * @param uri the URI as a string
     * @return the EditorEventManager (or null)
     */
    public EditorEventManager getEditorManagerFor(String uri) {
        FileEditor selectedEditor = FileEditorManager.getInstance(project).getSelectedEditor();

        if (selectedEditor == null) {
            return null;
        }
        VirtualFile currentOpenFile = selectedEditor.getFile();
        VirtualFile requestedFile = FileUtils.virtualFileFromURI(uri);
        if (currentOpenFile == null || requestedFile == null) {
            return null;
        }
        if (requestedFile.equals(currentOpenFile)) {
            return EditorEventManagerBase.forEditor(
                    (Editor) FileEditorManager.getInstance(project).getSelectedEditor());
        }
        if (uriToEditorManagers.containsKey(uri) && !uriToEditorManagers.get(uri).isEmpty()) {
            return (EditorEventManager) uriToEditorManagers.get(uri).toArray()[0];
        }

        return null;
    }

    public Set<EditorEventManager> getEditorManagersFor(String uri) {
        return uriToEditorManagers.get(uri);
    }

    /**
     * @return The request manager for this wrapper
     */
    public RequestManager getRequestManager() {
        return requestManager;
    }

    /**
     * @return whether the underlying connection to language languageServer is still active
     */
    public boolean isActive() {
        return launcherFuture != null && !launcherFuture.isDone() && !launcherFuture.isCancelled()
                && !alreadyShownTimeout && !alreadyShownCrash;
    }

    /**
     * Connects an editor to the languageServer.
     *
     * @param editor the editor
     */
    public void connect(Editor editor) {
        if (editor == null) {
            LOG.warn("editor is null for " + serverDefinition);
            return;
        }
        if (!FileUtils.isEditorSupported(editor)) {
            LOG.debug("Editor hosts a unsupported file type by the LS library.");
            return;
        }

        String uri = editorToURIString(editor);
        if (connectedEditors.contains(editor)) {
            return;
        }

        // Runs on this wrapper's dispatcher, so the project can have closed since the editor open
        // event was queued. Bail out rather than starting a server nothing will shut down.
        LspServerManager serverManager = LspServerManager.getInstanceIfCreated(project);
        if (serverManager == null) {
            LOG.debug("Skipping connect for " + uri + "; the project is no longer available");
            return;
        }
        serverManager.mapUri(uri, this);

        start();
        if (initializeFuture != null) {
            ServerCapabilities capabilities = getServerCapabilities();
            if (capabilities == null) {
                LOG.warn("Capabilities are null for " + serverDefinition);
                return;
            }

            initializeFuture.thenRun(() -> {
                if (connectedEditors.contains(editor)) {
                    return;
                }
                try {
                    Either<TextDocumentSyncKind, TextDocumentSyncOptions> syncOptions =
                            capabilities.getTextDocumentSync();
                    if (syncOptions != null) {
                        //Todo - Implement
                        //  SelectionListenerImpl selectionListener = new SelectionListenerImpl();
                        DocumentListenerImpl documentListener = new DocumentListenerImpl();
                        EditorMouseListenerImpl mouseListener = new EditorMouseListenerImpl();
                        EditorMouseMotionListenerImpl mouseMotionListener = new EditorMouseMotionListenerImpl();
                        LSPCaretListenerImpl caretListener = new LSPCaretListenerImpl();

                        ServerOptions serverOptions = new ServerOptions(capabilities);
                        EditorEventManager manager;
                        if (extManager != null) {
                            manager = extManager.getExtendedEditorEventManagerFor(editor, documentListener,
                                    mouseListener, mouseMotionListener, caretListener, requestManager, serverOptions,
                                    this);
                            if (manager == null) {
                                manager = new EditorEventManager(editor, documentListener, mouseListener,
                                        mouseMotionListener, caretListener,
                                        requestManager, serverOptions, this);
                            }
                        } else {
                            manager = new EditorEventManager(editor, documentListener, mouseListener,
                                    mouseMotionListener, caretListener,
                                    requestManager, serverOptions, this);
                        }
                        // selectionListener.setManager(manager);
                        documentListener.setManager(manager);
                        mouseListener.setManager(manager);
                        mouseMotionListener.setManager(manager);
                        caretListener.setManager(manager);
                        manager.registerListeners();
                        if (!urisUnderLspControl.contains(uri)) {
                            manager.documentEventManager.registerListeners();
                        }
                        urisUnderLspControl.add(uri);
                        connectedEditors.add(editor);
                        if (uriToEditorManagers.containsKey(uri)) {
                            uriToEditorManagers.get(uri).add(manager);
                        } else {
                            Set<EditorEventManager> set = new HashSet<>();
                            set.add(manager);
                            uriToEditorManagers.put(uri, set);
                            manager.documentOpened();
                        }
                        LOG.info("Created a manager for " + uri);
                        synchronized (toConnect) {
                            toConnect.remove(editor);
                        }
                        for (Editor ed : new HashSet<>(toConnect)) {
                            connect(ed);
                        }
                        // Triggers annotators since this is the first editor which starts the LS
                        // and annotators are executed before LS is bootstrap to provide diagnostics.
                        computableReadAction(() -> {
                            PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
                            if (psiFile != null) {
                                DaemonCodeAnalyzer.getInstance(project).restart(psiFile);
                            }
                            return null;
                        });
                    }
                } catch (Exception e) {
                    LOG.error(e);
                }
            });

        } else {
            synchronized (toConnect) {
                toConnect.add(editor);
            }
        }
    }

    /*
     * The shutdown request is sent from the client to the server. It asks the server to shut down, but to not exit \
     * (otherwise the response might not be delivered correctly to the client).
     * Only if the exit flag is true, particular server instance will exit.
     *
     * Synchronized so that concurrent stops (for example a stop queued on the dispatcher and a direct
     * stop from dispose or the shutdown hook) cannot both enter cleanup; the status guard turns the
     * second caller into a no-op.
     */
    public synchronized void stop(boolean exit) {
        if (this.status == STOPPED || this.status == STOPPING) {
            return;
        }
        setStatus(STOPPING);

        if (initializeFuture != null) {
            initializeFuture.cancel(true);
        }

        try {
            if (languageServer != null) {
                CompletableFuture<Object> shutdown = languageServer.shutdown();
                shutdown.get(getTimeout(SHUTDOWN), TimeUnit.MILLISECONDS);
                notifySuccess(Timeouts.SHUTDOWN);
                if (exit) {
                    languageServer.exit();
                }
            }
        } catch (Exception e) {
            // most likely closed externally.
            notifyFailure(Timeouts.SHUTDOWN);
            LOG.warn("exception occurred while trying to shut down", e);
        } finally {
            if (launcherFuture != null) {
                launcherFuture.cancel(true);
            }
            if (launcherExecutor != null) {
                launcherExecutor.shutdownNow();
                launcherExecutor = null;
            }
            if (serverDefinition != null) {
                serverDefinition.stop(projectRootPath);
            }
            for (Editor ed : new HashSet<>(connectedEditors)) {
                disconnect(ed);
            }

            // sadly this whole editor closing stuff runs asynchronously,
            // so we cannot be sure the state is really clean here...
            // therefore clear the mapping from here as it should be empty by now.
            uriToEditorManagers.clear();
            urisUnderLspControl.clear();
            launcherFuture = null;
            capabilitiesAlreadyRequested = false;
            initializeResult = null;
            initializeFuture = null;
            languageServer = null;
            setStatus(STOPPED);
        }
    }

    /**
     * Checks if the wrapper is already connected to the document at the given path.
     *
     * @param location file location
     * @return True if the given file is connected.
     */
    public boolean isConnectedTo(String location) {
        return urisUnderLspControl.contains(location);
    }

    /**
     * @return the LanguageServer
     */
    @Nullable
    public LanguageServer getServer() {
        start();
        if (initializeFuture != null && !initializeFuture.isDone()) {
            initializeFuture.join();
        }
        return languageServer;
    }

    /**
     * Starts the LanguageServer.
     */
    public void start() {
        if (status == STOPPED && !alreadyShownCrash && !alreadyShownTimeout) {
            setStatus(STARTING);
            try {
                Pair<InputStream, OutputStream> streams = serverDefinition.start(projectRootPath);
                InputStream inputStream = streams.getKey();
                OutputStream outputStream = streams.getValue();
                InitializeParams initParams = getInitParams();
                ExecutorService executorService = Executors.newCachedThreadPool();
                launcherExecutor = executorService;
                inFlightRequests = new ConcurrentHashMap<>();
                MessageHandler messageHandler = new MessageHandler(
                        serverDefinition.getServerListener(), () -> getStatus() != STOPPED,
                        this, this::getTraceLevel, inFlightRequests);
                if (extManager != null && extManager.getExtendedServerInterface() != null) {
                    Class<? extends LanguageServer> remoteServerInterFace = extManager.getExtendedServerInterface();
                    client = extManager.getExtendedClientFor(new ServerWrapperBaseClientContext(this));

                    Launcher<? extends LanguageServer> launcher = Launcher
                            .createLauncher(client, remoteServerInterFace, inputStream, outputStream, executorService,
                                    messageHandler);
                    languageServer = launcher.getRemoteProxy();
                    launcherFuture = launcher.startListening();
                } else {
                    client = new DefaultLanguageClient(new ServerWrapperBaseClientContext(this));
                    Launcher<LanguageServer> launcher = Launcher
                            .createLauncher(client, LanguageServer.class, inputStream, outputStream, executorService,
                                    messageHandler);
                    languageServer = launcher.getRemoteProxy();
                    launcherFuture = launcher.startListening();
                }
                messageHandler.setLanguageServer(languageServer);

                initializeFuture = languageServer.initialize(initParams).thenApply(res -> {
                    initializeResult = res;
                    LOG.info("Got initializeResult for " + serverDefinition + " ; " + projectRootPath);
                    if (extManager != null) {
                        requestManager = extManager.getExtendedRequestManagerFor(
                                this, languageServer, client, res.getCapabilities());
                        if (requestManager == null) {
                            requestManager = new DefaultRequestManager(
                                    this, languageServer, client, res.getCapabilities());
                        }
                    } else {
                        requestManager = new DefaultRequestManager(
                                this, languageServer, client, res.getCapabilities());
                    }
                    setStatus(STARTED);
                    // send the initialized message since some language servers depends on this message
                    requestManager.initialized(new InitializedParams());
                    setStatus(INITIALIZED);
                    // Only announce a level that differs from the protocol default. Sending
                    // $/setTrace unconditionally would put a notification on the wire for every
                    // server at startup that it did not receive before this feature existed.
                    if (traceLevel != LspTraceLevel.OFF) {
                        sendSetTrace(traceLevel);
                    }
                    return res;
                });
            } catch (LSPException | IOException | URISyntaxException e) {
                LOG.warn(e);
                invokeLater(() ->
                        notifier.showMessage(String.format("Can't start server due to %s", e.getMessage()),
                                MessageType.WARNING));
                dispose();
            }
        }
    }

    private InitializeParams getInitParams() throws URISyntaxException {
        InitializeParams initParams = new InitializeParams();
        String projectRootUri = FileUtils.pathToUri(projectRootPath);
        WorkspaceFolder workspaceFolder = new WorkspaceFolder(projectRootUri, this.project.getName());
        initParams.setWorkspaceFolders(Collections.singletonList(workspaceFolder));

        // workspace capabilities
        WorkspaceClientCapabilities workspaceClientCapabilities = new WorkspaceClientCapabilities();
        workspaceClientCapabilities.setApplyEdit(true);
        workspaceClientCapabilities.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities());
        workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities());
        workspaceClientCapabilities.setWorkspaceEdit(new WorkspaceEditCapabilities());
        workspaceClientCapabilities.setSymbol(new SymbolCapabilities());
        workspaceClientCapabilities.setWorkspaceFolders(true);
        workspaceClientCapabilities.setConfiguration(false);

        // text document capabilities
        TextDocumentClientCapabilities textDocumentClientCapabilities = new TextDocumentClientCapabilities();
        textDocumentClientCapabilities.setCodeAction(new CodeActionCapabilities());
        textDocumentClientCapabilities.getCodeAction().setCodeActionLiteralSupport(
                new CodeActionLiteralSupportCapabilities(new CodeActionKindCapabilities()));
        textDocumentClientCapabilities.getCodeAction().setResolveSupport(
                new CodeActionResolveSupportCapabilities(codeActionResolveProperties));
        textDocumentClientCapabilities.setCompletion(new CompletionCapabilities(new CompletionItemCapabilities(true)));
        textDocumentClientCapabilities.setDefinition(new DefinitionCapabilities());
        textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities());
        textDocumentClientCapabilities.setFormatting(new FormattingCapabilities());
        textDocumentClientCapabilities.setHover(new HoverCapabilities());
        textDocumentClientCapabilities.setOnTypeFormatting(new OnTypeFormattingCapabilities());
        textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities());
        textDocumentClientCapabilities.setReferences(new ReferencesCapabilities());
        textDocumentClientCapabilities.setRename(new RenameCapabilities());
        textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities());
        textDocumentClientCapabilities.setSynchronization(new SynchronizationCapabilities(true, true, true));

        FoldingRangeCapabilities foldingRangeCapabilities = new FoldingRangeCapabilities();
        foldingRangeCapabilities.setFoldingRangeKind(
                new FoldingRangeKindSupportCapabilities(List.of(
                        FoldingRangeKind.Comment, FoldingRangeKind.Imports, FoldingRangeKind.Region)));
        foldingRangeCapabilities.setFoldingRange(new FoldingRangeSupportCapabilities(true));
        foldingRangeCapabilities.setLineFoldingOnly(false);
        textDocumentClientCapabilities.setFoldingRange(foldingRangeCapabilities);

        initParams.setCapabilities(
                new ClientCapabilities(workspaceClientCapabilities, textDocumentClientCapabilities, null));
        initParams.setClientInfo(new ClientInfo(
                ApplicationInfo.getInstance().getVersionName(),
                ApplicationInfo.getInstance().getFullVersion()));

        // custom initialization options and initialize params provided by users
        initParams.setInitializationOptions(
                serverDefinition.getInitializationOptions(
                        URI.create(initParams.getWorkspaceFolders().get(0).getUri())));
        serverDefinition.customizeInitializeParams(initParams);
        return initParams;
    }

    public void logMessage(Message message) {
        if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            if (responseMessage.getError() != null && (responseMessage.getId()
                    .equals(Integer.toString(ResponseErrorCode.RequestCancelled.getValue())))) {
                LOG.error(new ResponseErrorException(responseMessage.getError()));
            }
        }
    }

    public Project getProject() {
        return project;
    }

    public ServerStatus getStatus() {
        return status;
    }

    /**
     * Returns the server's {@code initialize} response, or null if it has not initialized. Unlike
     * {@link #getServerCapabilities()}, never starts the server or waits for it.
     */
    @Nullable
    public InitializeResult getInitializeResult() {
        return initializeResult;
    }

    /**
     * Returns how many times the server has crashed since the last time the user was asked whether
     * to keep restarting it.
     */
    public int getCrashCount() {
        return crashCount.get();
    }

    private void setStatus(ServerStatus status) {
        this.status = status;
        recordLifecycle(status);
    }

    /**
     * Appends a {@code LIFECYCLE} entry for one status transition. {@code STARTING} is the first
     * transition a wrapper ever makes, so it is the one call site allowed to create this project's
     * {@link LspLogStore}; every later transition only records into a store that must already exist,
     * tolerating its absence the same way {@link #unmapUri(String)} tolerates a disposed project.
     */
    private void recordLifecycle(ServerStatus status) {
        LspLogStore store = status == STARTING
                ? LspLogStore.getInstance(project)
                : LspLogStore.getInstanceIfCreated(project);
        if (store != null) {
            store.append(this, LspLogSource.LIFECYCLE, org.eclipse.lsp4j.MessageType.Info, status.toString(), null);
        }
    }

    /**
     * Returns the current JSON-RPC trace level for this server. {@code OFF} until changed with
     * {@link #setTraceLevel(LspTraceLevel)}.
     */
    public LspTraceLevel getTraceLevel() {
        return traceLevel;
    }

    /**
     * Changes the JSON-RPC trace level {@link org.wso2.lsp4intellij.logging.TracingMessageConsumer}
     * reads for this server, and asks the server to match via {@code $/setTrace} if it is connected.
     */
    public void setTraceLevel(LspTraceLevel traceLevel) {
        this.traceLevel = traceLevel;
        sendSetTrace(traceLevel);
    }

    /**
     * Sends {@code $/setTrace} for the given level. A notification, so a server that does not
     * implement it has nothing to reply with; any failure sending it (including one thrown
     * synchronously by the proxy) is logged rather than allowed to break the caller.
     */
    private void sendSetTrace(LspTraceLevel traceLevel) {
        LanguageServer server = languageServer;
        if (server == null) {
            return;
        }
        try {
            server.setTrace(new SetTraceParams(traceLevel.traceValue()));
        } catch (Exception e) {
            LOG.debug("Server for " + serverDefinition + " did not accept $/setTrace", e);
        }
    }

    public void crashed(Exception e) {
        recordCrash(e);
        if (crashCount.incrementAndGet() <= 3) {
            reconnect();
        } else {
            invokeLater(() -> {
                if (alreadyShownCrash) {
                    reconnect();
                } else {
                    int response = Messages.showYesNoDialog(String.format(
                                    "LanguageServer for definition %s, project %s keeps crashing due to \n%s\n"
                                    , serverDefinition.toString(), project.getName(), e.getMessage()),
                            "Language Server Client Warning", "Keep Connected", "Disconnect", PlatformIcons.CHECK_ICON);
                    if (response == Messages.NO) {
                        int confirm = Messages.showYesNoDialog(
                                "All the language server based plugin features will be disabled.\n"
                                + "Do you wish to continue?", "",
                                PlatformIcons.WARNING_INTRODUCTION_ICON);
                        if (confirm == Messages.YES) {
                            // Disconnects from the language server.
                            stop(true);
                        } else {
                            reconnect();
                        }
                    } else {
                        reconnect();
                    }
                }
                alreadyShownCrash = true;
                crashCount.set(0);
            });
        }
    }

    /**
     * Appends a {@code LIFECYCLE} entry for a crash. Unlike {@link #recordLifecycle(ServerStatus)},
     * this never creates the store: a crash can only happen after {@code STARTING} already did.
     */
    private void recordCrash(Exception e) {
        LspLogStore store = LspLogStore.getInstanceIfCreated(project);
        if (store != null) {
            store.append(this, LspLogSource.LIFECYCLE, org.eclipse.lsp4j.MessageType.Error,
                    "Crashed: " + e.getMessage(), null);
        }
    }

    private void reconnect() {
        // Need to copy by value since connected editors gets cleared during 'stop()' invocation.
        final Set<String> connected = new HashSet<>(urisUnderLspControl);
        stop(true);
        for (String uri : connected) {
            connect(uri);
        }
    }

    public List<String> getConnectedFiles() {
        List<String> connected = new ArrayList<>();
        urisUnderLspControl.forEach(s -> {
            try {
                connected.add(new URI(sanitizeURI(s)).toString());
            } catch (URISyntaxException e) {
                LOG.warn(e);
            }
        });
        return connected;
    }

    /**
     * Cleanup when the project is disposed. This removes the project from static maps
     * to prevent memory leaks.
     */
    public synchronized void dispose() {
        stop(true);
        dispatcher.shutdownNow();
        IntellijLanguageClient.removeWrapper(this);
        // This server's log buffer is deliberately left in place: a server that has just died is
        // exactly the one whose log someone wants to read. LspLogStore drops every buffer when the
        // project itself is disposed.
    }

    /**
     * Drops the uri-to-wrapper mapping for this project, if the project still has a manager.
     * Tolerates its absence because the callers below are reached from {@link #stop(boolean)} and
     * {@link #dispose()}, which the platform can also drive while the project is already disposing.
     */
    private void unmapUri(String uri) {
        LspServerManager manager = LspServerManager.getInstanceIfCreated(project);
        if (manager != null) {
            manager.unmapUri(uri);
        }
    }

    /**
     * Disconnects an editor from the LanguageServer.
     *
     * @param editor The editor
     */
    public void disconnect(Editor editor) {
        EditorEventManager manager = EditorEventManagerBase.forEditor(editor);
        connectedEditors.remove(editor);
        if (manager != null) {
            manager.removeListeners();
            // URI captured at connect time — the editor's current URI may have drifted since.
            String uri = manager.getIdentifier().getUri();
            Set<EditorEventManager> set = uriToEditorManagers.get(uri);
            if (set != null) {
                set.remove(manager);
                if (set.isEmpty()) {
                    manager.documentClosed();
                    manager.documentEventManager.removeListeners();

                    uriToEditorManagers.remove(uri);
                    urisUnderLspControl.remove(uri);
                    unmapUri(uri);
                }
            }
        }

        if (connectedEditors.isEmpty()) {
            // Deferred to the pool so that the didClose notification queued by documentClosed()
            // is sent before the server is shut down.
            pool(() -> stop(true));
        }
    }

    /**
     * Disconnects an editor from the LanguageServer.
     * <p>
     * WARNING: only use this method if you have no editor instance and you restart all connections
     * to the language server for all open editors
     * prefer using disconnect(editor)
     *
     * @param uri        The file uri
     * @param projectUri The project root uri (unused: a wrapper only ever serves its own project,
     *                    so {@code this.project} is used instead; kept for signature compatibility)
     */
    public void disconnect(String uri, String projectUri) {
        unmapUri(sanitizeURI(uri));

        Set<EditorEventManager> managers = uriToEditorManagers.get(uri);
        if (managers == null) {
            return;
        }
        for (EditorEventManager manager : managers) {
            manager.removeListeners();
            manager.documentEventManager.removeListeners();
            connectedEditors.remove(manager.editor);
            Set<EditorEventManager> editorEventManagers = uriToEditorManagers.get(uri);
            if (editorEventManagers != null) {
                editorEventManagers.remove(manager);
                if (editorEventManagers.isEmpty()) {
                    uriToEditorManagers.remove(uri);
                    manager.documentClosed();
                }
            }
            urisUnderLspControl.remove(uri);
            unmapUri(sanitizeURI(uri));
        }
        if (connectedEditors.isEmpty()) {
            // Deferred to the pool so that the didClose notification queued by documentClosed()
            // is sent before the server is shut down.
            pool(() -> stop(true));
        }
    }

    private void connect(String uri) {
        List<Editor> editors = FileUtils.getAllOpenedEditorsForUri(project, uri);

        for (Editor editor : editors) {
            connect(editor);
        }
    }

    /**
     * Is the language server in a state where it can be restartable. Normally language server is
     * restartable if it has timeout or has a startup error.
     */
    public boolean isRestartable() {
        return status == STOPPED && (alreadyShownTimeout || alreadyShownCrash);
    }

    /**
     * Reset language server wrapper state so it can be started again if it was failed earlier.
     */
    public void restart() {
        pool(() -> {
            if (isRestartable()) {
                alreadyShownCrash = false;
                alreadyShownTimeout = false;
            } else {
                stop(true);
            }
            reloadEditors(project);
        });
    }

    /**
     * Returns the extension manager associated with this language server wrapper.
     *
     * @return The result can be null if there is not extension manager defined.
     */
    @Nullable
    public final LSPExtensionManager getExtensionManager() {
        return extManager;
    }
}

