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

package com.google.devtools.kythe.platform.shared;

import com.google.common.io.Files;
import com.google.common.util.concurrent.Futures;
import com.google.devtools.kythe.common.PathUtil;

import java.io.File;
import java.util.concurrent.Future;

/**
 * {@link FileDataProvider} that looks up file data using the local filesystem. Each lookup only
 * uses the path (not the digest) and prefixes the path with a given root directory.
 */
public class FileDataDirectory implements FileDataProvider {
  private final String rootDirectory;

  public FileDataDirectory(String rootDirectory) {
    this.rootDirectory = rootDirectory;
  }

  @Override
  public Future<byte[]> startLookup(String path, String digest) {
    try {
      return Futures.immediateFuture(
          Files.asByteSource(new File(PathUtil.join(rootDirectory, path))).read());
    } catch (Throwable t) {
      return Futures.immediateFailedFuture(t);
    }
  }
}