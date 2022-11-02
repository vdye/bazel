// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.FileContentsProxy;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.io.*;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.*;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link SkyFunction} for {@link FileValue}s.
 *
 * <p>Most of the complexity in the implementation results from wanting incremental correctness in
 * the presence of symlinks, esp. ancestor directory symlinks.
 */
public class VirtualFileFunction implements SkyFunction {
  private final AtomicReference<PathPackageLocator> pkgLocator;
  private final ImmutableList<Root> immutablePaths;

  public VirtualFileFunction(
      AtomicReference<PathPackageLocator> pkgLocator, BlazeDirectories directories) {
    this.pkgLocator = pkgLocator;
    this.immutablePaths =
        ImmutableList.of(
            Root.fromPath(directories.getOutputBase()),
            Root.fromPath(directories.getInstallBase()));
  }

  @Nullable
  @Override
  public FileValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException {

    RootedPath rootedPath = (RootedPath) skyKey.argument();
    // TODO: handle symlinks like FileFunction
    // TODO: use user-specified resolution function

    try {
      ProcessBuilder builder = new ProcessBuilder();
      builder.command("git", "-C", rootedPath.getRoot().toString(),
              "ls-files", "--error-unmatch", rootedPath.getRootRelativePath().toString());
      Process process = builder.start();
      if (process.waitFor() == 0) {
        FileContentsProxy proxy = new FileContentsProxy(0, 0, 0);
        return FileValue.value(
                null,
                null,
                null,
                rootedPath,
                new FileStateValue.SpecialFileStateValue(proxy, true),
                rootedPath,
                FileStateValue.NONEXISTENT_FILE_STATE_NODE);
      } else {
        return FileValue.value(
                null,
                null,
                null,
                rootedPath,
                FileStateValue.NONEXISTENT_FILE_STATE_NODE,
                rootedPath,
                FileStateValue.NONEXISTENT_FILE_STATE_NODE);
      }
    } catch (Exception ex) {
      return FileValue.value(
              null,
              null,
              null,
              rootedPath,
              FileStateValue.NONEXISTENT_FILE_STATE_NODE,
              rootedPath,
              FileStateValue.NONEXISTENT_FILE_STATE_NODE);
    }
  }
}
