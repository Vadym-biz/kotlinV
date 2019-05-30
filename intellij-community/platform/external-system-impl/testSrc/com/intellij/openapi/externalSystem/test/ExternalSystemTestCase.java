// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.externalSystem.test;

import com.intellij.compiler.artifacts.ArtifactsTestUtil;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.compiler.server.BuildManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfoRt;
import com.intellij.openapi.util.io.ByteArraySequence;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.impl.compiler.ArtifactCompileScope;
import com.intellij.task.ProjectTaskManager;
import com.intellij.testFramework.*;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.PathUtil;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.PathKt;
import com.intellij.util.io.TestFileSystemItem;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.SystemIndependent;
import org.junit.After;
import org.junit.Before;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * @author Vladislav.Soroka
 */
public abstract class ExternalSystemTestCase extends UsefulTestCase {
  private File ourTempDir;

  protected IdeaProjectTestFixture myTestFixture;
  protected Project myProject;
  protected File myTestDir;
  protected VirtualFile myProjectRoot;
  protected VirtualFile myProjectConfig;
  protected List<VirtualFile> myAllConfigs = new ArrayList<>();
  protected boolean useProjectTaskManager;

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    ensureTempDirCreated();

    myTestDir = new File(ourTempDir, getTestName(false));
    FileUtil.ensureExists(myTestDir);

    setUpFixtures();
    myProject = myTestFixture.getProject();

    EdtTestUtil.runInEdtAndWait(() -> ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        setUpInWriteAction();
      }
      catch (Throwable e) {
        try {
          tearDown();
        }
        catch (Exception e1) {
          e1.printStackTrace();
        }
        throw new RuntimeException(e);
      }
    }));

    List<String> allowedRoots = new ArrayList<>();
    collectAllowedRoots(allowedRoots);
    if (!allowedRoots.isEmpty()) {
      VfsRootAccess.allowRootAccess(myTestFixture.getTestRootDisposable(), ArrayUtilRt.toStringArray(allowedRoots));
    }
  }

  protected void collectAllowedRoots(List<String> roots) {
  }

  public static Collection<String> collectRootsInside(String root) {
    final List<String> roots = ContainerUtil.newSmartList();
    roots.add(root);
    FileUtil.processFilesRecursively(new File(root), file -> {
      try {
        String path = file.getCanonicalPath();
        if (!FileUtil.isAncestor(path, path, false)) {
          roots.add(path);
        }
      }
      catch (IOException ignore) {
      }
      return true;
    });

    return roots;
  }

  private void ensureTempDirCreated() throws IOException {
    if (ourTempDir != null) return;

    ourTempDir = new File(FileUtil.getTempDirectory(), getTestsTempDir());
    FileUtil.delete(ourTempDir);
    FileUtil.ensureExists(ourTempDir);
  }

  protected abstract String getTestsTempDir();

  protected void setUpFixtures() throws Exception {
    myTestFixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(getName(), useDirectoryBasedStorageFormat()).getFixture();
    myTestFixture.setUp();
  }

  protected boolean useDirectoryBasedStorageFormat() {
    return false;
  }

  protected void setUpInWriteAction() throws Exception {
    File projectDir = new File(myTestDir, "project");
    FileUtil.ensureExists(projectDir);
    myProjectRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir);
  }

  @After
  @Override
  public void tearDown() throws Exception {
    new RunAll(
      () -> {
        if (myProject != null && !myProject.isDisposed()) {
          PathKt.delete(ProjectUtil.getExternalConfigurationDir(myProject));
        }
      },
      () -> EdtTestUtil.runInEdtAndWait(() -> tearDownFixtures()),
      () -> myProject = null,
      () -> PathKt.delete(myTestDir.toPath()),
      () -> super.tearDown(),
      () -> resetClassFields(getClass())
    ).run();
  }

  protected void tearDownFixtures() {
    if (myTestFixture != null) {
      try {
        myTestFixture.tearDown();
      }
      catch (Exception ignored) {
      }
    }
    myTestFixture = null;
  }

  private void resetClassFields(final Class<?> aClass) {
    if (aClass == null) return;

    final Field[] fields = aClass.getDeclaredFields();
    for (Field field : fields) {
      final int modifiers = field.getModifiers();
      if ((modifiers & Modifier.FINAL) == 0
          && (modifiers & Modifier.STATIC) == 0
          && !field.getType().isPrimitive()) {
        field.setAccessible(true);
        try {
          field.set(this, null);
        }
        catch (IllegalAccessException e) {
          e.printStackTrace();
        }
      }
    }

    if (aClass == ExternalSystemTestCase.class) return;
    resetClassFields(aClass.getSuperclass());
  }

  @Override
  protected void runTest() throws Throwable {
    try {
      if (runInWriteAction()) {
        try {
          WriteAction.runAndWait(() -> super.runTest());
        }
        catch (Throwable throwable) {
          ExceptionUtil.rethrowAllAsUnchecked(throwable);
        }
      }
      else {
        super.runTest();
      }
    }
    catch (Exception throwable) {
      Throwable each = throwable;
      do {
        if (each instanceof HeadlessException) {
          printIgnoredMessage("Doesn't work in Headless environment");
          return;
        }
      }
      while ((each = each.getCause()) != null);
      throw throwable;
    }
  }

  @Override
  protected void invokeTestRunnable(@NotNull Runnable runnable) {
    runnable.run();
  }

  protected boolean runInWriteAction() {
    return false;
  }

  protected static String getRoot() {
    if (SystemInfoRt.isWindows) return "c:";
    return "";
  }

  protected static String getEnvVar() {
    if (SystemInfoRt.isWindows) return "TEMP";
    else if (SystemInfoRt.isLinux) return "HOME";
    return "TMPDIR";
  }

  protected String getProjectPath() {
    return myProjectRoot.getPath();
  }

  protected String getParentPath() {
    return myProjectRoot.getParent().getPath();
  }

  @SystemIndependent
  protected String path(@NotNull String relativePath) {
    return PathUtil.toSystemIndependentName(file(relativePath).getPath());
  }

  protected File file(@NotNull String relativePath) {
    return new File(getProjectPath(), relativePath);
  }

  protected Module createModule(String name) {
    return createModule(name, StdModuleTypes.JAVA);
  }

  protected Module createModule(final String name, final ModuleType type) {
    try {
      return WriteCommandAction.writeCommandAction(myProject).compute(() -> {
        VirtualFile f = createProjectSubFile(name + "/" + name + ".iml");
        Module module = ModuleManager.getInstance(myProject).newModule(f.getPath(), type.getId());
        PsiTestUtil.addContentRoot(module, f.getParent());
        return module;
      });
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected VirtualFile createProjectConfig(@NonNls String config) {
    return myProjectConfig = createConfigFile(myProjectRoot, config);
  }

  protected VirtualFile createConfigFile(final VirtualFile dir, String config) {
    final String configFileName = getExternalSystemConfigFileName();
    VirtualFile f = dir.findChild(configFileName);
    try {
      if (f == null) {
        f = WriteAction.computeAndWait(() -> {
          VirtualFile res = dir.createChildData(null, configFileName);
          return res;
        });
        myAllConfigs.add(f);
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    setFileContent(f, config, true);
    return f;
  }

  protected abstract String getExternalSystemConfigFileName();

  protected void createStdProjectFolders() throws IOException {
    createProjectSubDirs("src/main/java",
                         "src/main/resources",
                         "src/test/java",
                         "src/test/resources");
  }

  protected void createProjectSubDirs(String... relativePaths) throws IOException {
    for (String path : relativePaths) {
      createProjectSubDir(path);
    }
  }

  protected VirtualFile createProjectSubDir(String relativePath) throws IOException {
    File f = new File(getProjectPath(), relativePath);
    FileUtil.ensureExists(f);
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
  }

  protected VirtualFile createProjectSubFile(String relativePath) throws IOException {
    File f = new File(getProjectPath(), relativePath);
    FileUtil.ensureExists(f.getParentFile());
    FileUtil.ensureCanCreateFile(f);
    final boolean created = f.createNewFile();
    if (!created && !f.exists()) {
      throw new AssertionError("Unable to create the project sub file: " + f.getAbsolutePath());
    }
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
  }

  @NotNull
  protected VirtualFile createProjectJarSubFile(String relativePath, Pair<ByteArraySequence, String>... contentEntries) throws IOException {
    assertTrue("Use 'jar' extension for JAR files: '" + relativePath + "'", FileUtilRt.extensionEquals(relativePath, "jar"));
    File f = new File(getProjectPath(), relativePath);
    FileUtil.ensureExists(f.getParentFile());
    FileUtil.ensureCanCreateFile(f);
    final boolean created = f.createNewFile();
    if (!created) {
      throw new AssertionError("Unable to create the project sub file: " + f.getAbsolutePath());
    }

    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    JarOutputStream target = new JarOutputStream(new FileOutputStream(f), manifest);
    for (Pair<ByteArraySequence, String> contentEntry : contentEntries) {
      addJarEntry(contentEntry.first.getBytes(), contentEntry.second, target);
    }
    target.close();

    final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
    assertNotNull(virtualFile);
    final VirtualFile jarFile = JarFileSystem.getInstance().getJarRootForLocalFile(virtualFile);
    assertNotNull(jarFile);
    return jarFile;
  }

  private static void addJarEntry(byte[] bytes, String path, JarOutputStream target) throws IOException {
    JarEntry entry = new JarEntry(path.replace("\\", "/"));
    target.putNextEntry(entry);
    target.write(bytes);
    target.close();
  }

  protected VirtualFile createProjectSubFile(String relativePath, String content) throws IOException {
    VirtualFile file = createProjectSubFile(relativePath);
    setFileContent(file, content, false);
    return file;
  }


  protected void compileModules(final String... moduleNames) {
    if (useProjectTaskManager) {
      Module[] modules = Arrays.stream(moduleNames).map(moduleName -> getModule(moduleName)).toArray(Module[]::new);
      build(modules);
    }
    else {
      compile(createModulesCompileScope(moduleNames));
    }
  }

  protected void buildArtifacts(String... artifactNames) {
    if (useProjectTaskManager) {
      Artifact[] artifacts = Arrays.stream(artifactNames)
        .map(artifactName -> ArtifactsTestUtil.findArtifact(myProject, artifactName)).toArray(Artifact[]::new);
      build(artifacts);
    }
    else {
      compile(createArtifactsScope(artifactNames));
    }
  }

  private void build(@NotNull Object[] buildableElements) {
    final Semaphore semaphore = new Semaphore();
    semaphore.down();
    if (buildableElements instanceof Module[]) {
      ProjectTaskManager.getInstance(myProject).build((Module[])buildableElements, executionResult -> semaphore.up());
    }
    else if (buildableElements instanceof Artifact[]) {
      ProjectTaskManager.getInstance(myProject).build((Artifact[])buildableElements, executionResult -> semaphore.up());
    }
    else {
      assert false : "Unsupported buildableElements: " + Arrays.toString(buildableElements);
    }
    while (!semaphore.waitFor(100)) {
      if (SwingUtilities.isEventDispatchThread()) {
        UIUtil.dispatchAllInvocationEvents();
      }
    }
  }

  private void compile(@NotNull CompileScope scope) {
    try {
      CompilerTester tester = new CompilerTester(myProject, Arrays.asList(scope.getAffectedModules()), null);
      try {
        List<CompilerMessage> messages = tester.make(scope);
        for (CompilerMessage message : messages) {
          switch (message.getCategory()) {
            case ERROR:
              fail("Compilation failed with error: " + message.getMessage());
              break;
            case WARNING:
              System.out.println("Compilation warning: " + message.getMessage());
              break;
            case INFORMATION:
              break;
            case STATISTICS:
              break;
          }
        }
      }
      finally {
        tester.tearDown();
      }
    }
    catch (Exception e) {
      ExceptionUtil.rethrow(e);
    }
  }


  private CompileScope createModulesCompileScope(final String[] moduleNames) {
    final List<Module> modules = new ArrayList<>();
    for (String name : moduleNames) {
      modules.add(getModule(name));
    }
    return new ModuleCompileScope(myProject, modules.toArray(Module.EMPTY_ARRAY), false);
  }

  private CompileScope createArtifactsScope(String[] artifactNames) {
    List<Artifact> artifacts = new ArrayList<>();
    for (String name : artifactNames) {
      artifacts.add(ArtifactsTestUtil.findArtifact(myProject, name));
    }
    return ArtifactCompileScope.createArtifactsScope(myProject, artifacts);
  }

  protected Sdk setupJdkForModule(final String moduleName) {
    final Sdk sdk = true ? JavaAwareProjectJdkTableImpl.getInstanceEx().getInternalJdk() : createJdk();
    ModuleRootModificationUtil.setModuleSdk(getModule(moduleName), sdk);
    return sdk;
  }

  protected static Sdk createJdk() {
    return IdeaTestUtil.getMockJdk17();
  }

  protected Module getModule(final String name) {
    return getModule(myProject, name);
  }

  protected Module getModule(Project project, String name) {
    Module m = ReadAction.compute(() -> ModuleManager.getInstance(project).findModuleByName(name));
    assertNotNull("Module " + name + " not found", m);
    return m;
  }

  protected void assertExplodedLayout(String artifactName, String expected) {
    assertJarLayout(artifactName + " exploded", expected);
  }

  protected void assertJarLayout(String artifactName, String expected) {
    ArtifactsTestUtil.assertLayout(myProject, artifactName, expected);
  }

  protected void assertArtifactOutputPath(final String artifactName, final String expected) {
    ArtifactsTestUtil.assertOutputPath(myProject, artifactName, expected);
  }

  protected void assertArtifactOutputFileName(final String artifactName, final String expected) {
    ArtifactsTestUtil.assertOutputFileName(myProject, artifactName, expected);
  }

  protected void assertArtifactOutput(String artifactName, TestFileSystemItem fs) {
    final Artifact artifact = ArtifactsTestUtil.findArtifact(myProject, artifactName);
    final String outputFile = artifact.getOutputFilePath();
    assert outputFile != null;
    final File file = new File(outputFile);
    assert file.exists();
    fs.assertFileEqual(file);
  }

  protected static void setFileContent(final VirtualFile file, final String content, final boolean advanceStamps) {
    try {
      WriteAction.runAndWait(() -> {
        if (advanceStamps) {
          file.setBinaryContent(content.getBytes(StandardCharsets.UTF_8), -1, file.getTimeStamp() + 4000);
        }
        else {
          file.setBinaryContent(content.getBytes(StandardCharsets.UTF_8), file.getModificationStamp(), file.getTimeStamp());
        }
      });
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected static <T, U> void assertOrderedElementsAreEqual(Collection<U> actual, Collection<T> expected) {
    assertOrderedElementsAreEqual(actual, expected.toArray());
  }

  protected static <T> void assertUnorderedElementsAreEqual(Collection<T> actual, Collection<T> expected) {
    assertEquals(new HashSet<>(expected), new HashSet<>(actual));
  }

  protected static void assertUnorderedPathsAreEqual(Collection<String> actual, Collection<String> expected) {
    assertEquals(new SetWithToString<>(new THashSet<>(expected, FileUtil.PATH_HASHING_STRATEGY)),
                 new SetWithToString<>(new THashSet<>(actual, FileUtil.PATH_HASHING_STRATEGY)));
  }

  protected static <T> void assertUnorderedElementsAreEqual(T[] actual, T... expected) {
    assertUnorderedElementsAreEqual(Arrays.asList(actual), expected);
  }

  protected static <T> void assertUnorderedElementsAreEqual(Collection<T> actual, T... expected) {
    assertUnorderedElementsAreEqual(actual, Arrays.asList(expected));
  }

  protected static <T, U> void assertOrderedElementsAreEqual(Collection<U> actual, T... expected) {
    String s = "\nexpected: " + Arrays.asList(expected) + "\nactual: " + new ArrayList<>(actual);
    assertEquals(s, expected.length, actual.size());

    java.util.List<U> actualList = new ArrayList<>(actual);
    for (int i = 0; i < expected.length; i++) {
      T expectedElement = expected[i];
      U actualElement = actualList.get(i);
      assertEquals(s, expectedElement, actualElement);
    }
  }

  protected static <T> void assertContain(java.util.List<? extends T> actual, T... expected) {
    java.util.List<T> expectedList = Arrays.asList(expected);
    assertTrue("expected: " + expectedList + "\n" + "actual: " + actual.toString(), actual.containsAll(expectedList));
  }

  protected static <T> void assertDoNotContain(java.util.List<T> actual, T... expected) {
    java.util.List<T> actualCopy = new ArrayList<>(actual);
    actualCopy.removeAll(Arrays.asList(expected));
    assertEquals(actual.toString(), actualCopy.size(), actual.size());
  }

  protected boolean ignore() {
    printIgnoredMessage(null);
    return true;
  }

  public static void deleteBuildSystemDirectory() {
    BuildManager buildManager = BuildManager.getInstance();
    if (buildManager == null) return;
    Path buildSystemDirectory = buildManager.getBuildSystemDirectory();
    try {
      PathKt.delete(buildSystemDirectory);
      return;
    }
    catch (Exception ignore) {
    }
    try {
      FileUtil.delete(buildSystemDirectory.toFile());
    }
    catch (Exception e) {
      LOG.warn("Unable to remove build system directory.", e);
    }
  }

  private void printIgnoredMessage(String message) {
    String toPrint = "Ignored";
    if (message != null) {
      toPrint += ", because " + message;
    }
    toPrint += ": " + getClass().getSimpleName() + "." + getName();
    System.out.println(toPrint);
  }

  private static class SetWithToString<T> extends AbstractSet<T> {

    private final Set<T> myDelegate;

    SetWithToString(@NotNull Set<T> delegate) {
      myDelegate = delegate;
    }

    @Override
    public int size() {
      return myDelegate.size();
    }

    @Override
    public boolean contains(Object o) {
      return myDelegate.contains(o);
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
      return myDelegate.iterator();
    }

    @Override
    public boolean containsAll(Collection<?> c) {
      return myDelegate.containsAll(c);
    }

    @Override
    public boolean equals(Object o) {
      return myDelegate.equals(o);
    }

    @Override
    public int hashCode() {
      return myDelegate.hashCode();
    }
  }
}
