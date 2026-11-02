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
package org.wso2.lsp4intellij.contributors.icon;

import com.intellij.icons.AllIcons;
import com.intellij.icons.AllIcons.Nodes;
import com.intellij.openapi.util.IconLoader;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.SymbolKind;
import org.wso2.lsp4intellij.client.languageserver.ServerStatus;
import org.wso2.lsp4intellij.client.languageserver.serverdefinition.LanguageServerDefinition;

import java.util.HashMap;
import java.util.Map;

import javax.swing.Icon;

public class LSPDefaultIconProvider extends LSPIconProvider {

    private static final Icon GREEN = IconLoader.getIcon("/images/started.svg", LSPDefaultIconProvider.class);
    private static final Icon YELLOW = IconLoader.getIcon("/images/starting.svg", LSPDefaultIconProvider.class);
    private static final Icon RED = IconLoader.getIcon("/images/stopped.svg", LSPDefaultIconProvider.class);

    public Icon getCompletionIcon(CompletionItemKind kind) {

        if (kind == null) {
            return null;
        }

        switch (kind) {
            case Class:
                return Nodes.Class;
            case Enum:
                return Nodes.Enum;
            case Field:
                return Nodes.Field;
            case File:
                return AllIcons.FileTypes.Any_type;
            case Function:
                return Nodes.Function;
            case Interface:
                return Nodes.Interface;
            case Keyword:
                return Nodes.UpLevel;
            case Method:
                return Nodes.Method;
            case Module:
                return Nodes.Module;
            case Property:
                return Nodes.Property;
            case Reference:
                return Nodes.MethodReference;
            case Snippet:
                return Nodes.Static;
            case Text:
                return AllIcons.FileTypes.Text;
            case Unit:
                return Nodes.Artifact;
            case Variable:
                return Nodes.Variable;
            default:
                return Nodes.AnonymousClass;
        }
    }

    public Icon getSymbolIcon(SymbolKind kind) {

        if (kind == null) {
            return null;
        }

        switch (kind) {
            case Field:
            case EnumMember:
                return Nodes.Field;
            case Method:
                return Nodes.Method;
            case Variable:
                return Nodes.Variable;
            case Class:
                return Nodes.Class;
            case Constructor:
                return Nodes.ClassInitializer;
            case Enum:
                return Nodes.Enum;
            default:
                return Nodes.Tag;
        }
    }

    public Map<ServerStatus, Icon> getStatusIcons() {
        Map<ServerStatus, Icon> statusIconMap = new HashMap<>();
        statusIconMap.put(ServerStatus.STOPPED, RED);
        statusIconMap.put(ServerStatus.STARTING, YELLOW);
        statusIconMap.put(ServerStatus.STARTED, YELLOW);
        statusIconMap.put(ServerStatus.INITIALIZED, GREEN);
        statusIconMap.put(ServerStatus.STOPPING, YELLOW);
        return statusIconMap;
    }

    @Override
    public boolean isSpecificFor(LanguageServerDefinition serverDefinition) {
        return false;
    }
}
