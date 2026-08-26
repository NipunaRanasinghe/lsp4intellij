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
package org.wso2.lsp4intellij.client;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.UIUtil;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressNotification;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.requests.WorkspaceEditHandler;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.swing.Icon;

public class DefaultLanguageClient implements LanguageClient {

    @NotNull
    private static final Logger LOG = Logger.getInstance(DefaultLanguageClient.class);
    @NotNull
    private final Map<String, DynamicRegistrationMethods> registrations = new ConcurrentHashMap<>();
    @NotNull
    private final ClientContext context;
    protected boolean isModal = false;
    private final HashMap<String, ProgressNotificationItem> progressNotificationItems = new HashMap<>();

    /**
     * The title and message last shown for one {@code $/progress} token. Replaces
     * {@code groovy.lang.Tuple2<String, String>}, which carried the same two values by position
     * instead of by name.
     *
     * @param title the progress notification's title
     * @param message the progress notification's message
     */
    private record ProgressNotificationItem(String title, String message) {
    }

    public DefaultLanguageClient(@NotNull ClientContext context) {
        this.context = context;
    }

    /**
     * Resolves the "lsp" notification group on demand rather than holding it in a static field.
     * {@code NotificationGroupManager} is an application service, and the platform logs
     * "Class initialization must not depend on services" when a service is requested from a class
     * initializer. Resolving it per call matches how the "LSPProgressNotification" group is already
     * looked up in this class, and it costs a map lookup: the manager owns the group instances.
     */
    private static NotificationGroup stickyNotificationGroup() {
        return NotificationGroupManager.getInstance().getNotificationGroup("lsp");
    }

    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams params) {
        boolean response = WorkspaceEditHandler.applyEdit(params.getEdit(), "LSP edits");
        return CompletableFuture.supplyAsync(() -> new ApplyWorkspaceEditResponse(response));
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        return LanguageClient.super.configuration(configurationParams);
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return LanguageClient.super.workspaceFolders();
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        return CompletableFuture.runAsync(() -> params.getRegistrations().forEach(r -> {
            String id = r.getId();
            Optional<DynamicRegistrationMethods> method = DynamicRegistrationMethods.forName(r.getMethod());
            method.ifPresent(dynamicRegistrationMethods -> registrations.put(id, dynamicRegistrationMethods));

        }));
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        return CompletableFuture.runAsync(() -> params.getUnregisterations().forEach((Unregistration r) -> {
            String id = r.getId();
            Optional<DynamicRegistrationMethods> method = DynamicRegistrationMethods.forName(r.getMethod());
            if (registrations.containsKey(id)) {
                registrations.remove(id);
            } else {
                Map<DynamicRegistrationMethods, String> inverted = new HashMap<>();
                for (Map.Entry<String, DynamicRegistrationMethods> entry : registrations.entrySet()) {
                    inverted.put(entry.getValue(), entry.getKey());
                }
                if (method.isPresent() && inverted.containsKey(method.get())) {
                    registrations.remove(inverted.get(method.get()));
                }
            }
        }));
    }

    @Override
    public void telemetryEvent(Object o) {
        LOG.info(o.toString());
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams publishDiagnosticsParams) {
        String uri = FileUtils.sanitizeURI(publishDiagnosticsParams.getUri());
        List<Diagnostic> diagnostics = publishDiagnosticsParams.getDiagnostics();
        EditorEventManagerBase.diagnostics(uri, diagnostics);
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        String title = "Language Server message";
        String message = messageParams.getMessage();

        if (isModal) {
            ApplicationUtils.invokeLater(() -> {
                MessageType msgType = messageParams.getType();
                switch (msgType) {
                    case Error:
                        Messages.showErrorDialog(message, title);
                        break;
                    case Warning:
                        Messages.showWarningDialog(message, title);
                        break;
                    case Info:
                    case Log:
                        Messages.showInfoMessage(message, title);
                        break;
                    default:
                        LOG.warn("No message type for " + message);
                        break;
                }
            });
        } else {
            NotificationType type = getNotificationType(messageParams.getType());
            final Notification notification = new Notification(
                    "lsp", messageParams.getType().toString(), messageParams.getMessage(), type);
            notification.notify(context.getProject());
        }
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams showMessageRequestParams) {
        List<MessageActionItem> actions = showMessageRequestParams.getActions();
        String title = "Language Server " + showMessageRequestParams.getType().toString();
        String message = showMessageRequestParams.getMessage();
        MessageType msgType = showMessageRequestParams.getType();

        String[] options = new String[actions == null ? 0 : actions.size()];
        for (int i = 0, size = options.length; i < size; i++) {
            options[i] = actions.get(i).getTitle();
        }

        int exitCode;
        FutureTask<Integer> task;
        if (isModal) {
            Icon icon;
            switch (msgType) {
                case Error:
                    icon = UIUtil.getErrorIcon();
                    break;
                case Warning:
                    icon = UIUtil.getWarningIcon();
                    break;
                case Info:
                case Log:
                    icon = UIUtil.getInformationIcon();
                    break;
                default:
                    icon = null;
                    LOG.warn("No message type for " + message);
                    break;
            }

            task = new FutureTask<>(
                    () -> Messages.showDialog(message, title, options, 0, icon));
            ApplicationManager.getApplication().invokeAndWait(task);

            try {
                exitCode = task.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warn(e.getMessage());
                exitCode = -1;
            }

        } else {

            final Notification notification = stickyNotificationGroup()
                    .createNotification(title, null, message, getNotificationType(msgType));
            final CompletableFuture<Integer> integerCompletableFuture = new CompletableFuture<>();
            for (int i = 0, optionsSize = options.length; i < optionsSize; i++) {
                int finalI = i;
                notification.addAction(new NotificationAction(options[i]) {
                    @Override
                    public boolean isDumbAware() {
                        return true;
                    }

                    @Override
                    public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification notification) {
                        integerCompletableFuture.complete(finalI);
                        notification.expire();
                    }
                });
            }
            notification.whenExpired(() -> {
                if (!integerCompletableFuture.isDone()) {
                    integerCompletableFuture.complete(-1);
                }
            });
            notification.notify(context.getProject());

            try {
                exitCode = integerCompletableFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                LOG.warn(e.getMessage());
                exitCode = -1;
            }
        }
        return CompletableFuture.completedFuture(actions == null || exitCode < 0 ? null : actions.get(exitCode));
    }

    protected NotificationType getNotificationType(@NotNull MessageType messageType) {
        switch (messageType) {
            case Error:
                return NotificationType.ERROR;
            case Warning:
                return NotificationType.WARNING;
            case Info:
            case Log:
            default:
                return NotificationType.INFORMATION;
        }
    }

    @Override
    public void logMessage(MessageParams messageParams) {
        String message = messageParams.getMessage();
        MessageType msgType = messageParams.getType();

        switch (msgType) {
            case Error:
                LOG.error(message);
                break;
            case Warning:
                LOG.warn(message);
                break;
            case Info:
            case Log:
                LOG.info(message);
                break;
            default:
                LOG.warn("Unknown message type '" + msgType + "' for " + message);
                break;
        }
    }

    @NotNull
    protected final ClientContext getContext() {
        return context;
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        String token;
        if (params.getToken().getLeft() != null) {
            token = params.getToken().getLeft();
        } else if (params.getToken().getRight() != null) {
            token = params.getToken().getRight().toString();
        } else {
            return null;
        }
        ProgressNotificationItem progressNotificationItem =
                new ProgressNotificationItem("LSP Progress Notification", "");
        progressNotificationItems.put(token, progressNotificationItem);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        NotificationGroup notificationGroup =
                NotificationGroupManager.getInstance().getNotificationGroup("LSPProgressNotification");

        String token;
        if (params.getToken().getLeft() != null) {
            token = params.getToken().getLeft();
        } else if (params.getToken().getRight() != null) {
            token = params.getToken().getRight().toString();
        } else {
            return;
        }
        String title = progressNotificationItems.get(token).title();
        String message = progressNotificationItems.get(token).message();
        WorkDoneProgressNotification progressNotification = params.getValue().getLeft();
        if (progressNotification instanceof WorkDoneProgressBegin) {
            title = ((WorkDoneProgressBegin) progressNotification).getTitle();
            message = ((WorkDoneProgressBegin) progressNotification).getMessage();
            ProgressNotificationItem progressNotificationItem = new ProgressNotificationItem(title, message);
            if (progressNotificationItems.containsKey(token)) {
                progressNotificationItems.replace(token, progressNotificationItem);
            } else {
                progressNotificationItems.put(token, progressNotificationItem);
            }
        } else if (progressNotification instanceof WorkDoneProgressReport) {
            message = ((WorkDoneProgressReport) progressNotification).getMessage();
            if (progressNotificationItems.containsKey(token)) {
                title = progressNotificationItems.get(token).title();
            }
        } else if (progressNotification instanceof WorkDoneProgressEnd) {
            message = ((WorkDoneProgressEnd) progressNotification).getMessage();
            if (progressNotificationItems.containsKey(token)) {
                title = progressNotificationItems.get(token).title();
            }
        }
        String extension = null;
        if (context instanceof ServerWrapperBaseClientContext) {
            ServerWrapperBaseClientContext serverContext = (ServerWrapperBaseClientContext) context;
            LanguageServerWrapper wrapper = serverContext.getWrapper();
            if (wrapper != null && wrapper.serverDefinition != null) {
                extension = wrapper.serverDefinition.ext;
            }
        }
        if (extension != null) {
            title = " [" + extension + " extension" + "] " + title;
        }
        Notification notification =
                notificationGroup.createNotification(title, message, NotificationType.INFORMATION, null);
        Notifications.Bus.notify(notification);
    }
}
