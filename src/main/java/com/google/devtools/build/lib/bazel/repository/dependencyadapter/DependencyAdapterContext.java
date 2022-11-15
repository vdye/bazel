// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.github.difflib.patch.PatchFailedException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleEvent;
import com.google.devtools.build.lib.bazel.repository.DecompressorDescriptor;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue;
import com.google.devtools.build.lib.bazel.repository.PatchUtil;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkBaseExternalContext;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkPath;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.StructImpl;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.repository.RepositoryFetchProgress;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.rules.repository.WorkspaceAttributeMapper;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.ParamType;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.*;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.util.Map;

/** Starlark API for the dependency_adapter's context. */
@StarlarkBuiltin(
    name = "adapter_ctx",
    category = DocCategory.BUILTIN,
    doc =
        "The context of the dependency adapter containing"
            + " helper functions and information about attributes. You get a adapter_ctx object"
            + " as an argument to the <code>implementation</code> function when you create a"
            + " dependency adapter.")
public class DependencyAdapterContext extends StarlarkBaseExternalContext {
  private final Rule rule;
  private final Path workspaceRoot;
  private final StructImpl attrObject;

  /**
   * Create a new context (adapter_ctx) object for a Starlark dependency adapter ({@code rule}
   * argument).
   */
  DependencyAdapterContext(
      Rule rule,
      Path outputDirectory,
      Environment environment,
      Map<String, String> env,
      DownloadManager downloadManager,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      StarlarkSemantics starlarkSemantics,
      @Nullable RepositoryRemoteExecutor remoteExecutor,
      Path workspaceRoot)
      throws EvalException {
    super(
        outputDirectory,
        environment,
        env,
        downloadManager,
        timeoutScaling,
        processWrapper,
        starlarkSemantics,
        remoteExecutor);
    this.rule = rule;
    this.workspaceRoot = workspaceRoot;
    WorkspaceAttributeMapper attrs = WorkspaceAttributeMapper.of(rule);
    ImmutableMap.Builder<String, Object> attrBuilder = new ImmutableMap.Builder<>();
    for (String name : attrs.getAttributeNames()) {
      if (!name.equals("$local")) {
        // Attribute values should be type safe
        attrBuilder.put(
            Attribute.getStarlarkName(name), Attribute.valueToStarlark(attrs.getObject(name)));
      }
    }
    attrObject = StructProvider.STRUCT.create(attrBuilder.buildOrThrow(), "No such attribute '%s'");
  }

  @Override
  protected String getIdentifyingStringForLogging() {
    return "dependency adapter";
  }

  @StarlarkMethod(
      name = "workspace_root",
      structField = true,
      doc = "The path to the root workspace of the bazel invocation.")
  public StarlarkPath getWorkspaceRoot() {
    return new StarlarkPath(workspaceRoot);
  }

  @StarlarkMethod(
      name = "attr",
      structField = true,
      doc =
          "A struct to access the values of the attributes. The values are provided by "
              + "the user (if not, a default value is used).")
  public StructImpl getAttr() {
    return attrObject;
  }

  @Override
  protected boolean isRemotable() {
    Object remotable = rule.getAttr("$remotable");
    if (remotable != null) {
      return (Boolean) remotable;
    }
    return false;
  }

  @Override
  protected ImmutableMap<String, String> getRemoteExecProperties() throws EvalException {
    return ImmutableMap.copyOf(
        Dict.cast(
            getAttr().getValue("exec_properties"), String.class, String.class, "exec_properties"));
  }

  @Override
  public String toString() {
    return "adapter_ctx[" + rule.getLabel() + "]";
  }
}
