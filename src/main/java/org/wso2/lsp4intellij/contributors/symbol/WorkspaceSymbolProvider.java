/*
 * Copyright (c) 2018-2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.lsp4intellij.contributors.symbol;

import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.icon.LSPIconProvider;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.FileUtils;
import org.wso2.lsp4intellij.utils.GUIUtils;

/**
 * The workspace symbole provider implementation based on LSP
 *
 * @author gayanper
 */
public class WorkspaceSymbolProvider {

  private static final Logger LOG = Logger.getInstance(WorkspaceSymbolProvider.class);

  public List<NavigationItem> workspaceSymbols(String name, Project project) {
    final Set<LanguageServerWrapper> serverWrappers = IntellijLanguageClient
        .getProjectToLanguageWrappers()
        .getOrDefault(FileUtils.projectToUri(project), Collections.emptySet());

    final WorkspaceSymbolParams symbolParams = new WorkspaceSymbolParams(name);
    return serverWrappers.stream().filter(s -> s.getStatus() == ServerStatus.INITIALIZED)
        .flatMap(server -> collectSymbol(server, server.getRequestManager(), symbolParams))
        .map(s -> createNavigationItem(s, project)).collect(Collectors.toList());
  }

  private LSPNavigationItem createNavigationItem(LSPSymbolResult result, Project project) {
    final LSPIconProvider iconProviderFor = GUIUtils.getIconProviderFor(result.getDefinition());
    final SymbolInformation information = result.getSymbolInformation();
    final Location location = information.getLocation();
    return new LSPNavigationItem(information.getName(),
        information.getContainerName(), iconProviderFor.getSymbolIcon(information.getKind()),
        project, FileUtils.URIToVFS(location.getUri()),
        location.getRange().getStart().getLine(),
        location.getRange().getStart().getCharacter());
  }

  @SuppressWarnings("squid:S2142")
  private Stream<? extends LSPSymbolResult> collectSymbol(LanguageServerWrapper wrapper,
      RequestManager requestManager,
      WorkspaceSymbolParams symbolParams) {
    final CompletableFuture<List<? extends SymbolInformation>> request = requestManager
        .symbol(symbolParams);

    if (request == null) {
      return Stream.empty();
    }

    try {
      List<? extends SymbolInformation> symbolInformations = request
          .get(20000, TimeUnit.MILLISECONDS);
      wrapper.notifySuccess(Timeouts.SYMBOLS);
      return symbolInformations.stream()
          .map(si -> new LSPSymbolResult(si, wrapper.getServerDefinition()));
    } catch (TimeoutException e) {
      LOG.warn(e);
      wrapper.notifyFailure(Timeouts.SYMBOLS);
    } catch (ExecutionException | InterruptedException e) {
      LOG.warn(e);
      wrapper.crashed(e);
    }
    return Stream.empty();
  }

  private static class LSPSymbolResult {

    private SymbolInformation symbolInformation;
    private LanguageServerDefinition definition;

    public LSPSymbolResult(SymbolInformation symbolInformation,
        LanguageServerDefinition definition) {
      this.symbolInformation = symbolInformation;
      this.definition = definition;
    }

    public SymbolInformation getSymbolInformation() {
      return symbolInformation;
    }

    public LanguageServerDefinition getDefinition() {
      return definition;
    }
  }
}
