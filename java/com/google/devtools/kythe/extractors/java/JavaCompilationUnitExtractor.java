/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.kythe.extractors.java;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.devtools.kythe.common.FormattingLogger;
import com.google.devtools.kythe.common.PathUtil;
import com.google.devtools.kythe.extractors.shared.CompilationDescription;
import com.google.devtools.kythe.extractors.shared.CompilationFileInputComparator;
import com.google.devtools.kythe.extractors.shared.ExtractionException;
import com.google.devtools.kythe.extractors.shared.ExtractorUtils;
import com.google.devtools.kythe.extractors.shared.FileVNames;
import com.google.devtools.kythe.proto.Analysis.CompilationUnit;
import com.google.devtools.kythe.proto.Analysis.CompilationUnit.FileInput;
import com.google.devtools.kythe.proto.Analysis.FileData;
import com.google.devtools.kythe.proto.Storage.VName;
import com.google.devtools.kythe.util.DeleteRecursively;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symtab;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Extracts all required information (set of source files, class paths,
 * and compiler options) from a java compilation command and stores the
 * information to replay the compilation.
 *
 * The extractor runs the Javac compiler to get a exact description of all files
 * required for the compilation as a whole. It then creates CompilationUnit entries
 * for each source file. We do not do this on a per file basis as the Java
 * Compiler takes too long to do this.
 */
public class JavaCompilationUnitExtractor {
  private static final FormattingLogger logger =
      FormattingLogger.getLogger(JavaCompilationUnitExtractor.class);

  private static final String JAR_SCHEME = "jar";

  @VisibleForTesting
  static final String JAR_ROOT = "!jar!";

  private final String jdkJar;
  private final String rootDirectory;
  private final boolean trackUnusedDependencies;
  private final FileVNames fileVNames;

  /**
   * Creates an instance of the JavaExtractor to store java compilation information in an .kindex
   * file.
   */
  public JavaCompilationUnitExtractor(String corpus) throws ExtractionException {
    this(corpus, ExtractorUtils.getCurrentWorkingDirectory());
  }

  /**
   * Creates an instance of the JavaExtractor to store java compilation information in an .kindex
   * file.
   */
  public JavaCompilationUnitExtractor(String corpus, String rootDirectory)
      throws ExtractionException {
    this(FileVNames.staticCorpus(corpus), rootDirectory);
  }

  /**
   * Creates an instance of the JavaExtractor to store java compilation information in an .kindex
   * file.
   */
  public JavaCompilationUnitExtractor(FileVNames fileVNames) throws ExtractionException {
    this(fileVNames, ExtractorUtils.getCurrentWorkingDirectory());
  }

  public JavaCompilationUnitExtractor(FileVNames fileVNames, String rootDirectory)
      throws ExtractionException {
    this(fileVNames, rootDirectory, false);
  }
  public JavaCompilationUnitExtractor(FileVNames fileVNames, String rootDirectory,
      boolean trackUnusedDependencies) throws ExtractionException {
    this.fileVNames = fileVNames;
    this.trackUnusedDependencies = trackUnusedDependencies;
    this.rootDirectory = rootDirectory;

    // Remove trailing dots.  Interesting trivia: in some build systems,
    // the java.home variable is terminated with "/bin/..".
    // However, this is not the case for the class files
    // that we are trying to filter.
    String tmpjdkJar = null;
    try {
      tmpjdkJar = "file:" + new File(
          PathUtil.dirname(System.getProperty("java.home"))).getCanonicalPath();
    } catch (IOException e) {
      throw new ExtractionException(
          "JDK path not found: " + PathUtil.dirname(System.getProperty("java.home")), e, false);
    }
    jdkJar = tmpjdkJar;
  }

  public String getRootDirectory() {
    return rootDirectory;
  }

  private CompilationUnit buildCompilationUnit(String target, Iterable<String> options,
      Iterable<FileInput> requiredInputs, boolean hasErrors,
      Set<String> newSourcePath, Set<String> newClassPath, List<String> sourceFiles,
      String outputPath, Set<String> unusedJars) {
    CompilationUnit.Builder unit = CompilationUnit.newBuilder();
    unit.setVName(VName.newBuilder()
        .setSignature(target)
        .setLanguage("java"));
    unit.addAllArgument(options);
    unit.addArgument("-sourcepath");
    unit.addArgument(Joiner.on(":").join(newSourcePath));
    unit.addArgument("-cp");
    unit.addArgument(Joiner.on(":").join(newClassPath));
    unit.setHasCompileErrors(hasErrors);
    unit.addAllRequiredInput(requiredInputs);
    for (String sourceFile : sourceFiles) {
      unit.addSourceFile(sourceFile);
    }
    unit.setOutputKey(outputPath);
    return unit.build();
  }

  @Deprecated
  public CompilationDescription extract(String target, Iterable<String> sources,
      Iterable<String> classpath, Iterable<String> sourcepath, Iterable<String> options,
      String outputPath) throws ExtractionException {
    Iterable<String> empty = Collections.emptyList();
    return extract(target, sources, classpath, sourcepath, empty, empty, options, outputPath);
  }

  /**
   * Indexes a compilation unit to the bigtable. The extraction process will
   * try to build a minimum set of what is needed to replay the compilation.
   * To do this it runs the java compiler, and tracks all .class & .java files
   * that are needed. It then builds up a new classpath & sourcepath that only
   * contains the minimum set of paths required to replay the compilation.
   *
   * New classpath: because we extract classes from the jars into a temp path
   * that needs to be set. Also we only use the classpaths that are actually
   * used, not the ones that are provided.
   * New sourcepath: as we're doing a partial compilation, we need to set up the
   * source path to correctly load any files that are not the main compilation
   * but are needed to perform compilation. This is not required when doing a
   * full compilation as all sources are automatically loaded.
   *
   * Next we store all required files in the bigtable and writes the
   * CompilationUnit to the bigtable.
   *
   * @throws ExtractionException if anything blocks the indexing to be completed.
   */
  public CompilationDescription extract(String target, Iterable<String> sources,
      Iterable<String> classpath, Iterable<String> sourcepath, Iterable<String> processorpath,
      Iterable<String> processors, Iterable<String> options, String outputPath)
      throws ExtractionException {
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(sources);
    Preconditions.checkNotNull(classpath);
    Preconditions.checkNotNull(sourcepath);
    Preconditions.checkNotNull(processorpath);
    Preconditions.checkNotNull(processors);
    Preconditions.checkNotNull(options);
    Preconditions.checkNotNull(outputPath);

    final AnalysisResults results = runJavaAnalysisToExtractCompilationDetails(sources, classpath,
        sourcepath, processorpath, processors, options, outputPath);

    List<FileData> fileContents = ExtractorUtils.convertBytesToFileDatas(results.fileContents);
    List<FileInput> compilationFileInputs = Lists.newLinkedList();
    for (FileData data : fileContents) {
      String relativePath = results.relativePaths.get(data.getInfo().getPath());
      VName vname = fileVNames.lookupBaseVName(relativePath);
      if (vname.getPath().isEmpty()) {
        vname = vname.toBuilder().setPath(data.getInfo().getPath()).build();
      }
      String sourceBasename = results.sourceFileNames.get(data.getInfo().getPath());
      if (sourceBasename != null && vname.getPath().endsWith(".java")
          && !vname.getPath().endsWith(sourceBasename)) {
        Path fixedPath = Paths.get(vname.getPath()).getParent().resolve(sourceBasename);
        vname = vname.toBuilder().setPath(fixedPath.toString()).build();
      }
      compilationFileInputs.add(FileInput.newBuilder()
          .setInfo(data.getInfo())
          .setVName(vname)
          .build());
    }
    Collections.sort(compilationFileInputs, CompilationFileInputComparator.getComparator());

    CompilationUnit compilationUnit = buildCompilationUnit(target, removeOutputDirOption(options),
        compilationFileInputs, results.hasErrors, results.newSourcePath, results.newClassPath,
        results.explicitSources,
        ExtractorUtils.tryMakeRelative(rootDirectory, outputPath), results.unusedJars);
    return new CompilationDescription(compilationUnit, fileContents);
  }

  /**
   * Removing -d option before we build the compilation unit data for storing.
   *
   * @return a new list of options which does not contain {@code -d} and its argument
   */
  private static List<String> removeOutputDirOption(Iterable<String> options) {
    List<String> newOptions = Lists.newArrayList(options);
    int dashDIndex = newOptions.indexOf("-d");
    if (dashDIndex >= 0 && (dashDIndex + 1 < newOptions.size())) {
      newOptions.subList(dashDIndex, dashDIndex + 2).clear();
    }
    int dashSIndex = newOptions.indexOf("-s");
    if (dashSIndex >= 0 && (dashSIndex + 1 < newOptions.size())) {
      newOptions.subList(dashSIndex, dashSIndex + 2).clear();
    }
    return newOptions;
  }

  /**
   * If the code has wildcard imports (e.g. import foo.bar.*) but doesn't
   * actually use any of the imports, errors will happen.
   * We don't get callbacks for file open of these files (since they aren't used)
   * but when java runs it will report errors if it can't find any files to match
   * the wildcard.  So we add one matching file here.
   */
  private void findOnDemandImportedFiles(
      Iterable<? extends CompilationUnitTree> compilationUnits,
      UsageAsInputReportingFileManager fileManager) throws ExtractionException {
    // Maps package names to source files that wildcard import them.
    Multimap<String, String> pkgs = HashMultimap.create();

    // Javac synthesizes an "import java.lang.*" for every compilation unit.
    pkgs.put("java.lang", "*.java");

    for (CompilationUnitTree unit : compilationUnits) {
      for (ImportTree importTree : unit.getImports()) {
        if (importTree.isStatic()) {
          continue;
        }
        String qualifiedIdentifier = importTree.getQualifiedIdentifier().toString();
        if (!qualifiedIdentifier.endsWith(".*")) {
          continue;
        }
        pkgs.put(qualifiedIdentifier.substring(0, qualifiedIdentifier.length() - 2),
            unit.getSourceFile().getName());
      }
    }

    for (Map.Entry<String, Collection<String>> pkg : pkgs.asMap().entrySet()) {
      try {
        JavaFileObject firstClass = Iterables.getFirst(fileManager.list(
            StandardLocation.CLASS_PATH, pkg.getKey(), Sets.newHashSet(Kind.CLASS), false), null);
        if (firstClass == null) {
          firstClass = Iterables.getFirst(fileManager.list(
              StandardLocation.PLATFORM_CLASS_PATH, pkg.getKey(), Sets.newHashSet(Kind.CLASS),
              false), null);
        }
        if (firstClass != null) {
          firstClass.getCharContent(true);
        }
        JavaFileObject firstSource = Iterables.getFirst(fileManager.list(
            StandardLocation.SOURCE_PATH, pkg.getKey(), Sets.newHashSet(Kind.SOURCE), false), null);
        if (firstSource != null) {
          firstSource.getCharContent(true);
        }
      } catch (IOException e) {
        throw new ExtractionException(String.format(
            "Unable to extract files used for on demand imports in {%s}",
            Joiner.on(", ").join(pkg.getValue())), e, false);
      }
    }
  }

  /**
   * Determines -sourcepath arguments to add to the compilation
   * unit based on the package name. This is needed as the sharded analysis
   * will need to resolve dependent source files. Also locates sources that
   * do not follow the package == path convention and list them as explicit
   * sources.
   */
  private void getAdditionalSourcePaths(
          Iterable<? extends CompilationUnitTree> compilationUnits, AnalysisResults results) {

    for (CompilationUnitTree compilationUnit : compilationUnits) {
      ExpressionTree packageExpression = compilationUnit.getPackageName();
      if (packageExpression != null) {
        String packageName = packageExpression.toString();
        if (!Strings.isNullOrEmpty(packageName)) {
          // If the source file specifies a package, we try to find that
          // package name in the path to the source file and assume
          // the correct sourcepath to add is the directory containing that
          // package.
          String packageSubDir = packageName.replace('.', '/');
          String path = compilationUnit.getSourceFile().toUri().getPath();
          // This needs to be lastIndexOf as there are source jars that
          // contain the same package name in the files contained in them
          // as the path the source jars live in. As we extract the source
          // jars, we end up with a double named path.
          int index = path.lastIndexOf(packageSubDir);
          if (index >= 0) {
            String root = ExtractorUtils.tryMakeRelative(rootDirectory,
                path.substring(0, index));
            results.newSourcePath.add(root);
          }
        }
      }
    }
  }

  private void findRequiredFiles(UsageAsInputReportingFileManager fileManager,
      Map<URI, String> sourceFiles, AnalysisResults results) throws ExtractionException {
    for (JavaFileObject fileObject : fileManager.getUsages()) {
      processRequiredInput(fileObject, fileManager, sourceFiles, results);
    }
  }

  private void processRequiredInput(JavaFileObject requiredInput,
      JavaFileManager fileManager, Map<URI, String> sourceFiles, AnalysisResults results)
      throws ExtractionException {
    URI uri = requiredInput.toUri();
    boolean isJarPath = false;

    if (uri.getScheme().equals(JAR_SCHEME)) {
      isJarPath = true;
      uri = URI.create(uri.getRawSchemeSpecificPart());
    }

    // We only support .class & .java files right now.
    switch (requiredInput.getKind()) {
      case CLASS:
      case SOURCE:
        break;
      default:
        throw new IllegalStateException(String.format("Unsupported java file kind: '%s' for '%s'",
            requiredInput.getKind().name(), uri));
    }

    // If the file was part of the JDK we do not store it as the JDK is tied
    // to the analyzer we'll run on this information later on.
    if (isJarPath && uri.toString().startsWith(jdkJar)) {
      return;
    }

    // Make the path relative to the indexer (e.g. a subdir of corpus/).
    // If not possible, we store the fullpath.
    String relativePath =
        ExtractorUtils.tryMakeRelative(rootDirectory, uri.getRawSchemeSpecificPart());

    String strippedPath = relativePath;
    if (isJarPath) {
      // If the file came from a jar file, we strip that out as we
      // don't care where it came from, it was not as a source file in source control.
      // We turn it in to a fake path that starts with !JAR!/
      // This is done so we do not need to download the entire jar file for each
      // file's compilation (e.g. instead of downloading 200MB we only download 60K
      // for analyzing the kythe java indexer).
      int splitIndex = strippedPath.indexOf('!');
      String rest = strippedPath.substring(splitIndex + 1);
      switch(requiredInput.getKind()) {
        case CLASS:
          results.newClassPath.add(JAR_ROOT);
          break;
        case SOURCE:
          results.newSourcePath.add(JAR_ROOT);
          break;
      }
      strippedPath = JAR_ROOT + rest;
    }
    if (!isJarPath && requiredInput.getKind() == Kind.CLASS) {
      // If the class file was on disk, we need to infer the correct classpath to add.
      String binaryName = fileManager.inferBinaryName(StandardLocation.CLASS_OUTPUT, requiredInput);
      if (binaryName == null) {
        binaryName = fileManager.inferBinaryName(StandardLocation.CLASS_PATH, requiredInput);
      }
      if (binaryName == null) {
        binaryName = fileManager.inferBinaryName(StandardLocation.SOURCE_PATH, requiredInput);
      }
      if (binaryName == null) {
        binaryName = fileManager.inferBinaryName(
            StandardLocation.PLATFORM_CLASS_PATH, requiredInput);
      }
      if (binaryName == null) {
        throw new ExtractionException(
            String.format("unable to infer classpath for %s", strippedPath), false);
      }

      // Java package names map to folders on disk, so if we want to find the right directory
      // we replace each . with a /.
      String csubdir = binaryName.replace('.', '/');
      int cindex = strippedPath.indexOf(csubdir);
      if (cindex <= 0) {
        throw new ExtractionException(
          String.format("unable to infer classpath for %s from %s, %s", strippedPath, csubdir,
            binaryName), false);
      } else {
        results.newClassPath.add(strippedPath.substring(0, cindex));
      }
    }
    // TODO: Better way to identify generated source files?
    if (requiredInput.getKind() == Kind.SOURCE && strippedPath.contains("-gensrc.jar.files/")) {
      results.explicitSources.add(strippedPath);
      int i = strippedPath.indexOf("-gensrc.jar.files/") + 17;
      results.newSourcePath.add(strippedPath.substring(0, i));
    }
    if (!results.fileContents.containsKey(strippedPath)) {
      try {
        // Retrieve the contents of the file.
        InputStream stream = requiredInput.openInputStream();
        if (stream.markSupported()) {
          // The stream has already been read by the compiler, we need to reset it
          // so we can read it as well.
          stream.reset();
        }
        byte[] data = ByteStreams.toByteArray(stream);
        if (data.length == 0) {
          logger.warningfmt("Empty java source file: %s", strippedPath);
        }
        results.fileContents.put(strippedPath, data);
        results.relativePaths.put(strippedPath, relativePath);
        if (sourceFiles.containsKey(requiredInput.toUri())) {
          results.sourceFileNames.put(strippedPath, sourceFiles.get(requiredInput.toUri()));
        }
      } catch (IOException e) {
        throw new ExtractionException(
            String.format("Unable to read file content of %s", strippedPath), false);
      }
    }
  }

  private static class AnalysisResults {
    // Map from strippedPath to an input's relative path to the corpus root.
    final Map<String, String> relativePaths = Maps.newLinkedHashMap();
    // Map from strippedPath to an input's contents.
    final Map<String, byte[]> fileContents = Maps.newLinkedHashMap();
    // Map from strippedPath to an input's true source basename. This is usually only needed for
    // non-public top-level classes where their filename does not match the path derived from their
    // fully-qualified name.
    final Map<String, String> sourceFileNames = Maps.newHashMap();

    // We build a new sourcepath & classpath that contain the minimum set of paths
    // as well as the modified set of paths that are needed to analyze the single compilation unit.
    // This is done to speed up analysis.
    final Set<String> newSourcePath = Sets.newHashSet();
    final Set<String> newClassPath = Sets.newHashSet();
    final List<String> explicitSources = Lists.newArrayList();
    final Set<String> unusedJars = Sets.newHashSet();
    boolean hasErrors = false;
  }

  private AnalysisResults runJavaAnalysisToExtractCompilationDetails(
      Iterable<String> sources, Iterable<String> classpath, Iterable<String> sourcepath,
      Iterable<String> processorpath, Iterable<String> processors, Iterable<String> options,
      String outputPath) throws ExtractionException {

    AnalysisResults results = new AnalysisResults();

    // We will initialize and run the Javac compiler to detect which dependencies
    // the current compilation has.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      // TODO(schroederc): provide link to further context
      throw new IllegalStateException("Could not get system Java compiler; are you missing the JDK?");
    }

    DiagnosticCollector<JavaFileObject> diagnosticsCollector =
        new DiagnosticCollector<JavaFileObject>();

    StandardJavaFileManager standardFileManager =
        compiler.getStandardFileManager(diagnosticsCollector, null, null);

    // We insert a filemanager that wraps the standard filemanager and records the compiler's
    // usage of .java & .class files.
    final UsageAsInputReportingFileManager fileManager =
        new UsageAsInputReportingFileManager(standardFileManager);

    Iterable<? extends JavaFileObject> sourceFiles = fileManager.getJavaFileForSources(sources);

    // We need to modify the options to the compiler to pass in -classpath and
    // -sourcepath arguments.
    List<String> completeOptions = Lists.newArrayList(options);

    if (!hasEncodingOption(completeOptions)) {
      completeOptions.add("-encoding");
      completeOptions.add(Charsets.UTF_8.name());
    }

    String classPathJoined = Joiner.on(":").join(classpath);
    String sourcePathJoined = Joiner.on(":").join(sourcepath);
    if (!Strings.isNullOrEmpty(classPathJoined)) {
      completeOptions.add("-cp");
      completeOptions.add(classPathJoined);
    }
    if (!Strings.isNullOrEmpty(sourcePathJoined)) {
      completeOptions.add("-sourcepath");
      completeOptions.add(sourcePathJoined);
    }

    // Generate class files in a temporary directory
    Path tempDir;
    try {
      tempDir = Files.createTempDirectory("javac_extractor");
    } catch (IOException ioe) {
      throw new ExtractionException("Unable to create temporary .class output directory", ioe, true);
    }
    for (int i = 0; i < completeOptions.size(); i++) {
      if ("-d".equals(completeOptions.get(i))) {
        completeOptions.remove(i);
        completeOptions.remove(i);
      }
    }
    completeOptions.add("-d");
    completeOptions.add(tempDir.toString());

    Iterable<? extends CompilationUnitTree> compilationUnits;
    Symtab syms;
    try {
      // Launch the java compiler with our modified settings and the filemanager wrapper
      CompilationTask task =
          compiler.getTask(null, fileManager, diagnosticsCollector, completeOptions, null,
              sourceFiles);

      List<Processor> procs = Lists.<Processor>newArrayList(new ProcessAnnotation(fileManager));
      if (!Iterables.isEmpty(processors)) {
        List<URL> urls = Lists.newArrayList();
        for (String path : processorpath) {
          try {
            urls.add(new File(path).toURI().toURL());
          } catch (MalformedURLException e) {
            throw new ExtractionException("Bad processorpath entry", e, false);
          }
        }
        // TODO: Use a more restricted ClassLoader to prevent class path skew?
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), parent);
        for (String processor : processors) {
          try {
            procs.add(loader.loadClass(processor).asSubclass(Processor.class).newInstance());
          } catch (ClassNotFoundException|IllegalAccessException|InstantiationException e) {
            throw new ExtractionException("Bad processor entry", e, false);
          }
        }
      }

      JavacTask javacTask = (JavacTask) task;
      javacTask.setProcessors(procs);
      syms = Symtab.instance(((JavacTaskImpl) javacTask).getContext());
      try {
        // In order for the compiler to load all required .java & .class files
        // we need to have it go through parsing, analysis & generate phases.
        // Unfortunately the latter is needed to get a complete list, this was found
        // as we were breaking on analyzing certain files.
        compilationUnits = javacTask.parse();
        javacTask.generate();
      } catch (com.sun.tools.javac.util.Abort e) {
        // Type resolution issues, the diagnostics will give hints on what's going wrong.
        for (Diagnostic<? extends JavaFileObject> diagnostic :
                 diagnosticsCollector.getDiagnostics()) {
          if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
            logger.severefmt("Fatal error in compiler: %s", diagnostic.getMessage(Locale.ENGLISH));
          }
        }
        throw new ExtractionException("Fatal error while running javac compiler.", e, false);
      } catch (IOException e) {
        throw new ExtractionException("Fatal error while running javac compiler.", e, false);
      }
    } finally {
      try {
        DeleteRecursively.delete(tempDir);
      } catch (IOException ioe) {
        logger.severefmt(ioe, "Failed to delete temporary directory " + tempDir);
      }
    }
    // If we encountered any compilation errors, we report them even though we
    // still store the compilation information for this set of sources.
    for (Diagnostic<? extends JavaFileObject> diag : diagnosticsCollector.getDiagnostics()) {
      if (diag.getKind() == Diagnostic.Kind.ERROR) {
        results.hasErrors = true;
        if (diag.getSource() != null) {
          logger.severefmt("compiler error: %s(%d): %s", diag.getSource().getName(),
              diag.getLineNumber(), diag.getMessage(Locale.ENGLISH));
        } else {
          logger.severefmt("compiler error: %s", diag.getMessage(Locale.ENGLISH));
        }
      }
    }

    for (String source : sources) {
      results.explicitSources.add(ExtractorUtils.tryMakeRelative(rootDirectory, source));
    }

    getAdditionalSourcePaths(compilationUnits, results);

    // Find files potentially used for resolving .* imports.
    findOnDemandImportedFiles(compilationUnits, fileManager);
    // We accumulate all file contents from the java compiler so we can store it in the bigtable.
    findRequiredFiles(fileManager, mapClassesToSources(syms), results);
    if (trackUnusedDependencies) {
      findUnusedDependencies(classpath, fileManager, results);
    }
    return results;
  }

  /** Returns a map from a classfile's {@link URI} to its sourcefile path's basename. */
  private static Map<URI, String> mapClassesToSources(Symtab syms) {
    Map<URI, String> sourceBaseNames = Maps.newHashMap();
    for (ClassSymbol sym : syms.classes.values()) {
      if (sym.sourcefile != null && sym.classfile != null) {
        String basename = new File(sym.sourcefile.toUri().getPath()).getName();
        if (!basename.endsWith(".java")) {
          throw new IllegalStateException("Invalid sourcefile name: '" + basename + "'");
        }
        sourceBaseNames.put(sym.classfile.toUri(), basename);
      }
    }
    return sourceBaseNames;
  }

  private void findUnusedDependencies(Iterable<String> classpaths,
      UsageAsInputReportingFileManager fileManager,
      AnalysisResults results) {
    Set<String> jars = Sets.newHashSet();
    for (String classpath : classpaths) {
      if (!classpath.endsWith(".jar")) {
        continue;
      }
      jars.add(ExtractorUtils.tryMakeRelative(rootDirectory, classpath));
    }
    for (JavaFileObject usage : fileManager.getUsages()) {
      URI uri = usage.toUri();
      if (!uri.getScheme().equals(JAR_SCHEME)) {
        continue;
      }
      // Javac uses the pattern of JAR:<jarfile-url>!<classfile> for class files inside jar files.
      String jar = uri.getRawSchemeSpecificPart().split("!")[0];
      uri = URI.create(jar);
      String jarPath = ExtractorUtils.tryMakeRelative(rootDirectory,
          uri.getRawSchemeSpecificPart());
      if (jars.contains(jarPath)) {
        jars.remove(jarPath);
      }
    }
    results.unusedJars.addAll(jars);
  }

  private boolean hasEncodingOption(List<String> options) {
    return options.contains("-encoding");
  }
}
