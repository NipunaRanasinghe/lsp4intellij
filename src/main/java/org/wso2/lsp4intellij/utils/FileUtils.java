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
package org.wso2.lsp4intellij.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Various file / uri related methods
 */
public class FileUtils {
    private final static OS os = (System.getProperty("os.name").toLowerCase().contains("win")) ? OS.WINDOWS : OS.UNIX;
    private final static String COLON_ENCODED = "%3A";
    public final static String SPACE_ENCODED = "%20";
    private final static String URI_FILE_BEGIN = "file:";
    private final static String URI_VALID_FILE_BEGIN = "file:///";
    private final static char URI_PATH_SEP = '/';

    private static Logger LOG = Logger.getInstance(FileUtils.class);

    public static String extFromPsiFile(PsiFile psiFile) {
        return psiFile.getVirtualFile().getExtension();
    }

    public static Editor editorFromPsiFile(PsiFile psiFile) {
        return editorFromVirtualFile(psiFile.getVirtualFile(), psiFile.getProject());
    }

    public static Editor editorFromUri(String uri, Project project) {
        return editorFromVirtualFile(virtualFileFromURI(uri), project);
    }

    public static Editor editorFromVirtualFile(VirtualFile file, Project project) {
        FileEditor[] allEditors = FileEditorManager.getInstance(project).getAllEditors(file);
        if (allEditors.length > 0 && allEditors[0] instanceof TextEditor) {
            return ((TextEditor) allEditors[0]).getEditor();
        }
        return null;
    }

    public static VirtualFile virtualFileFromURI(String uri) {
        try {
            return LocalFileSystem.getInstance().findFileByIoFile(new File(new URI(sanitizeURI(uri))));
        } catch (URISyntaxException e) {
            LOG.warn(e);
            return null;
        }
    }

    /**
     * Returns a file type given an editor
     *
     * @param editor The editor
     * @return The FileType
     */
    public static FileType fileTypeFromEditor(Editor editor) {
        return FileDocumentManager.getInstance().getFile(editor.getDocument()).getFileType();
    }

    /**
     * Transforms an editor (Document) identifier to an LSP identifier
     *
     * @param editor The editor
     * @return The TextDocumentIdentifier
     */
    public static TextDocumentIdentifier editorToLSPIdentifier(Editor editor) {
        return new TextDocumentIdentifier(editorToURIString(editor));
    }

    /**
     * Returns the URI string corresponding to an Editor (Document)
     *
     * @param editor The Editor
     * @return The URI
     */
    public static String editorToURIString(Editor editor) {
        return sanitizeURI(VFSToURI(FileDocumentManager.getInstance().getFile(editor.getDocument())));
    }

    /**
     * Returns the URI string corresponding to a VirtualFileSystem file
     *
     * @param file The file
     * @return the URI
     */
    public static String VFSToURI(VirtualFile file) {
        try {
            return sanitizeURI(new URL(file.getUrl().replace(" ", SPACE_ENCODED)).toURI().toString());
        } catch (MalformedURLException | URISyntaxException e) {
            LOG.warn(e);
            return null;
        }
    }

    /**
     * Fixes common problems in uri, mainly related to Windows
     *
     * @param uri The uri to sanitize
     * @return The sanitized uri
     */
    public static String sanitizeURI(String uri) {
        if (uri != null) {
            StringBuilder reconstructed = new StringBuilder();
            String uriCp = uri.replaceAll(" ", SPACE_ENCODED); //Don't trust servers
            if (!uri.startsWith(URI_FILE_BEGIN)) {
                LOG.warn("Malformed uri : " + uri);
                return uri; //Probably not an uri
            } else {
                uriCp = uriCp.substring(URI_FILE_BEGIN.length());
                while (uriCp.startsWith(Character.toString(URI_PATH_SEP))) {
                    uriCp = uriCp.substring(1);
                }
                reconstructed.append(URI_VALID_FILE_BEGIN);
                if (os == OS.UNIX) {
                    return reconstructed.append(uriCp).toString();
                } else {
                    reconstructed.append(uriCp.substring(0, uriCp.indexOf(URI_PATH_SEP)));
                    char driveLetter = reconstructed.charAt(URI_VALID_FILE_BEGIN.length());
                    if (Character.isLowerCase(driveLetter)) {
                        reconstructed.setCharAt(URI_VALID_FILE_BEGIN.length(), Character.toUpperCase(driveLetter));
                    }
                    if (reconstructed.toString().endsWith(COLON_ENCODED)) {
                        reconstructed.delete(reconstructed.length() - 3, reconstructed.length());
                    }
                    if (!reconstructed.toString().endsWith(":")) {
                        reconstructed.append(":");
                    }
                    return reconstructed.append(uriCp.substring(uriCp.indexOf(URI_PATH_SEP))).toString();
                }
            }
        } else {
            return null;
        }
    }

    /**
     * Transforms an URI string into a VFS file
     *
     * @param uri The uri
     * @return The virtual file
     */
    public static VirtualFile URIToVFS(String uri) {
        try {
            return LocalFileSystem.getInstance().findFileByIoFile(new File(new URI(sanitizeURI(uri))));
        } catch (URISyntaxException e) {
            LOG.warn(e);
            return null;
        }
    }

    /**
     * Returns the project base dir uri given an editor
     *
     * @param editor The editor
     * @return The project whose the editor belongs
     */
    public static String editorToProjectFolderUri(Editor editor) {
        return pathToUri(editorToProjectFolderPath(editor));
    }

    public static String editorToProjectFolderPath(Editor editor) {
        if (editor != null && editor.getProject() != null && editor.getProject().getBasePath() != null) {
            return new File(editor.getProject().getBasePath()).getAbsolutePath();
        }
        return null;
    }

    /**
     * Transforms a path into an URI string
     *
     * @param path The path
     * @return The uri
     */
    public static String pathToUri(@Nullable String path) {
        return path != null ? sanitizeURI(new File(path.replace(" ", SPACE_ENCODED)).toURI().toString()) : null;
    }

    public static String projectToUri(Project project) {
        if (project != null && project.getBasePath() != null) {
            return pathToUri(new File(project.getBasePath()).getAbsolutePath());
        }
        return null;
    }

    public static String documentToUri(Document document) {
        return sanitizeURI(VFSToURI(FileDocumentManager.getInstance().getFile(document)));
    }

    /**
     * Object representing the OS type (Windows or Unix)
     */
    public enum OS {
        WINDOWS, UNIX
    }

}
