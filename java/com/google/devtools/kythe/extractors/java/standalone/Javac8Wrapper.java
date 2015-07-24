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

package com.google.devtools.kythe.extractors.java.standalone;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.kythe.extractors.java.JavaCompilationUnitExtractor;
import com.google.devtools.kythe.extractors.shared.CompilationDescription;

import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.CommandLine;
import com.sun.tools.javac.main.Main;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Options;

import java.util.EnumSet;
import java.util.List;

/**
 * A class that wraps javac to extract compilation information and write it to an index file.
 */
public class Javac8Wrapper extends AbstractJavacWrapper {
  @Override
  protected CompilationDescription processCompilation(String[] cleanedUpArguments,
      JavaCompilationUnitExtractor javaCompilationUnitExtractor) throws Exception {
    Main main = new Main("kythe_javac8");
    Context context = new Context();
    JavacFileManager.preRegister(context);
    Options options = Options.instance(context);
    main.setOptions(options);
    main.filenames = Sets.newHashSet();
    main.classnames = new ListBuffer<String>();

    // Use javac's argument parser to get the list of source files
    List<String> sources = getSourceList(main.processArgs(CommandLine.parse(cleanedUpArguments)));

    // Retrieve the list of class paths provided by the -classpath argument.
    List<String> classPaths = splitPaths(options.get(Option.CLASSPATH));

    // Retrieve the list of source paths provided by the -sourcepath argument.
    List<String> sourcePaths = splitPaths(options.get(Option.SOURCEPATH));

    // Retrieve the list of processor paths provided by the -processorpath argument.
    List<String> processorPaths = splitPaths(options.get(Option.PROCESSORPATH));

    // Retrieve the list of processors provided by the -processor argument.
    List<String> processors = splitPaths(options.get(Option.PROCESSOR));

    EnumSet<Option> claimed = EnumSet.of(
        Option.CLASSPATH, Option.SOURCEPATH,
        Option.PROCESSORPATH, Option.PROCESSOR);

    // Retrieve all other javac options.
    List<String> completeOptions = Lists.newArrayList();
    for (Option opt : Option.values()) {
      if (!claimed.contains(opt)) {
        String value = options.get(opt);
        if (value != null) {
          if (opt.getText().endsWith(":")) {
            completeOptions.add(opt.getText() + value);
          } else {
            completeOptions.add(opt.getText());
            if (!value.equals(opt.getText())) {
              completeOptions.add(value);
            }
          }
        }
      }
    }

    // Get the output directory for this target.
    String outputDirectory = options.get(Option.D);
    if (outputDirectory == null) {
      outputDirectory = ".";
    }

    String analysisTarget =
        readEnvironmentVariable("KYTHE_ANALYSIS_TARGET", createTargetFromSourceFiles(sources));
    return javaCompilationUnitExtractor.extract(analysisTarget, sources,
        classPaths, sourcePaths, processorPaths, processors, completeOptions, outputDirectory);
  }

  @Override
  protected void passThrough(String[] args) throws Exception {
    com.sun.tools.javac.Main.main(args);
  }

  public static void main(String[] args) {
    new Javac8Wrapper().process(args);
  }
}
