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

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.BinaryLightVirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import java.io.File;
import java.net.URISyntaxException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
public class FileUtilsTest {

  @PrepareForTest(FileEditorManager.class)
  @Test
  public void testEditorFromVirtualFile() {
    VirtualFile file = PowerMockito.mock(VirtualFile.class);
    Project project = PowerMockito.mock(Project.class);

    Editor editor = PowerMockito.mock(Editor.class);
    TextEditor textEditor = PowerMockito.mock(TextEditor.class);
    PowerMockito.when(textEditor.getEditor()).thenReturn(editor);

    FileEditorManagerEx fileEditorManagerEx = PowerMockito.mock(FileEditorManagerEx.class);
    PowerMockito.mockStatic(FileEditorManager.class);
    PowerMockito.when(fileEditorManagerEx.getAllEditors(file))
        .thenReturn(new FileEditor[]{textEditor})
        .thenReturn(new FileEditor[0]);
    PowerMockito.when(FileEditorManager.getInstance(project)).thenReturn(fileEditorManagerEx);

    Assert.assertEquals(editor, FileUtils.editorFromVirtualFile(file, project));

    Assert.assertNull(FileUtils.editorFromVirtualFile(file, project));
  }

  @PrepareForTest(LocalFileSystem.class)
  @Test
  public void testVirtualFileFromURI() {
    LocalFileSystem localFileSystem = PowerMockito.mock(LocalFileSystem.class);
    PowerMockito.mockStatic(LocalFileSystem.class);
    PowerMockito.when(LocalFileSystem.getInstance()).thenReturn(localFileSystem);
    PowerMockito.when(LocalFileSystem.getInstance().findFileByIoFile(Mockito.any()))
        .thenReturn(new BinaryLightVirtualFile("testFile"));

    Assert.assertEquals(new BinaryLightVirtualFile("testFile").toString(),
        FileUtils.virtualFileFromURI("file://foobar").toString());
  }

  @PrepareForTest(LocalFileSystem.class)
  @Test
  public void testVirtualFileFromURINull() {
    PowerMockito.mockStatic(LocalFileSystem.class);
    PowerMockito.when(LocalFileSystem.getInstance()).thenThrow(URISyntaxException.class);

    Assert.assertNull(FileUtils.virtualFileFromURI("foobar"));
  }

  @Test
  public void testVFSToURI() {
    VirtualFile virtualFile = PowerMockito.mock(VirtualFile.class);
    PowerMockito.when(virtualFile.getUrl()).thenReturn("file://fooBar");

    Assert.assertEquals("file:///fooBar", FileUtils.VFSToURI((virtualFile)));
  }

  @Test
  public void testVFSToURINull() {
    Assert.assertNull(FileUtils.VFSToURI((new LightVirtualFile())));
  }

  @Test
  public void testSanitizeURIUnix() {
    PowerMockito.mockStatic(System.class);
    PowerMockito.when(System.getProperty(Mockito.anyString())).thenReturn("Linux");

    Assert.assertNull(FileUtils.sanitizeURI(null));

    Assert.assertEquals("fooBar", FileUtils.sanitizeURI("fooBar"));
    Assert.assertEquals("file:///fooBar", FileUtils.sanitizeURI("file://fooBar"));
  }

  @PrepareForTest(FileUtils.class)
  @Test
  public void testSanitizeURIWindows() {
    PowerMockito.mockStatic(System.class);
    PowerMockito.when(System.getProperty(Mockito.anyString())).thenReturn("Windows");

    Assert.assertEquals("file:///Foo:/Bar", FileUtils.sanitizeURI("file:foo%3A/Bar"));
  }

  @PrepareForTest(LocalFileSystem.class)
  @Test
  public void testURIToVFS() {
    LocalFileSystem localFileSystem = PowerMockito.mock(LocalFileSystem.class);
    PowerMockito.mockStatic(LocalFileSystem.class);
    PowerMockito.when(LocalFileSystem.getInstance()).thenReturn(localFileSystem);
    PowerMockito.when(LocalFileSystem.getInstance().findFileByIoFile(Mockito.any()))
        .thenReturn(new BinaryLightVirtualFile("testFile"));

    Assert.assertEquals(new BinaryLightVirtualFile("testFile").toString(),
        FileUtils.URIToVFS("file://foobar").toString());
  }

  @PrepareForTest(LocalFileSystem.class)
  @Test
  public void testURIToVFSNull() {
    PowerMockito.mockStatic(LocalFileSystem.class);
    PowerMockito.when(LocalFileSystem.getInstance()).thenThrow(URISyntaxException.class);
    Assert.assertNull(FileUtils.URIToVFS("foobar"));
  }

  @PrepareForTest(FileUtils.class)
  @Test
  public void testEditorToProjectFolderPath() throws Exception {
    Assert.assertNull(FileUtils.editorToProjectFolderPath(null));

    PowerMockito.spy(FileUtils.class);
    Editor editor = PowerMockito.mock(Editor.class);
    PowerMockito.when(editor.getProject()).thenReturn(PowerMockito.mock(Project.class));
    PowerMockito.when(editor.getProject().getBasePath()).thenReturn("test");

    File file = PowerMockito.mock(File.class);
    PowerMockito.when(file.getAbsolutePath()).thenReturn("fooBar");
    PowerMockito.whenNew(File.class).withAnyArguments().thenReturn(file);

    Assert.assertEquals("fooBar", FileUtils.editorToProjectFolderPath(editor));
  }

  @PrepareForTest(FileUtils.class)
  @Test
  public void testProjectToUri() {
    Assert.assertNull(FileUtils.projectToUri(null));

    PowerMockito.spy(FileUtils.class);
    Project project = PowerMockito.mock(Project.class);
    PowerMockito.when(project.getBasePath()).thenReturn("test");
    PowerMockito.when(FileUtils.pathToUri(Mockito.anyString())).thenReturn("fooBar");

    Assert.assertEquals("fooBar", FileUtils.projectToUri(project));
  }

  @Test
  public void testIsFileSupported() {
    VirtualFile virtualFile1 = PowerMockito.mock(VirtualFile.class);
    PowerMockito.when(virtualFile1.getUrl()).thenReturn("jar:");

    VirtualFile virtualFile2 = PowerMockito.mock(VirtualFile.class);
    PowerMockito.when(virtualFile2.getUrl()).thenReturn("");

    Assert.assertFalse(FileUtils.isFileSupported(null));
    Assert.assertFalse(FileUtils.isFileSupported(new BinaryLightVirtualFile("testFile")));
    Assert.assertFalse(FileUtils.isFileSupported(virtualFile1));
    Assert.assertFalse(FileUtils.isFileSupported(virtualFile2));
  }

  @PrepareForTest(FileDocumentManager.class)
  @Test
  public void testIsEditorSupported() {
    VirtualFile virtualFile1 = PowerMockito.mock(VirtualFile.class);
    PowerMockito.when(virtualFile1.getUrl()).thenReturn("jar:");

    PowerMockito.mockStatic(FileDocumentManager.class);
    FileDocumentManager fileDocumentManager = PowerMockito.mock(FileDocumentManager.class);
    PowerMockito.when(fileDocumentManager.getFile(Mockito.mock(Document.class)))
        .thenReturn(virtualFile1);
    PowerMockito.when(FileDocumentManager.getInstance()).thenReturn(fileDocumentManager);

    Assert.assertFalse(FileUtils.isEditorSupported(Mockito.mock(Editor.class)));
  }
}
