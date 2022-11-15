// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.dependencyadapter;

import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkSemantics;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A helper for invoking the dependency adapter to resolve virtual build files.
 */
public class DependencyAdapterHelper {
  private final BlazeDirectories directories;
  private final DownloadManager downloadManager;
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;

  private double timeoutScaling = 1.0;
  @Nullable private ProcessWrapper processWrapper = null;
  @Nullable private RepositoryRemoteExecutor repositoryRemoteExecutor = null;

  public DependencyAdapterHelper(
      BlazeDirectories directories,
      Supplier<Map<String, String>> clientEnvironmentSupplier,
      DownloadManager downloadManager) {
    this.directories = directories;
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
    this.downloadManager = downloadManager;
  }

  public DependencyAdapterContext createContext(Rule dependencyAdapter,
                                                StarlarkSemantics starlarkSemantics,
                                                Environment env) throws EvalException {
    Path workingDirectory =
            directories
                    .getOutputBase()
                    .getRelative(LabelConstants.DEPENDENCY_ADAPTER_WORKING_DIRECTORY_LOCATION);

    return new DependencyAdapterContext(
                    dependencyAdapter,
                    workingDirectory,
                    env,
                    clientEnvironmentSupplier.get(),
                    downloadManager,
                    timeoutScaling,
                    processWrapper,
                    starlarkSemantics,
                    repositoryRemoteExecutor,
                    directories.getWorkspace());
  }
}
