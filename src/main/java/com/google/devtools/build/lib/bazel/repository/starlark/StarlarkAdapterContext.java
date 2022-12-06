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

package com.google.devtools.build.lib.bazel.repository.starlark;

import com.github.difflib.patch.PatchFailedException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.lib.bazel.debug.WorkspaceRuleEvent;
import com.google.devtools.build.lib.bazel.repository.DecompressorDescriptor;
import com.google.devtools.build.lib.bazel.repository.DecompressorValue;
import com.google.devtools.build.lib.bazel.repository.PatchUtil;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
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
    name = "repository_ctx",
    category = DocCategory.BUILTIN,
    doc =
        "The context of the dependency adapter containing"
            + " helper functions and information about attributes. You get a adapter_ctx object"
            + " as an argument to the <code>implementation</code> function when you create a"
            + " repository rule.")
public class StarlarkAdapterContext extends StarlarkBaseExternalContext {
  private final Rule rule;
  private final RepositoryName repoName;
  private final PathPackageLocator packageLocator;
  private final Path workspaceRoot;
  private final StructImpl attrObject;
  private final ImmutableSet<PathFragment> ignoredPatterns;
  private final SyscallCache syscallCache;

  /**
   * Create a new context (repository_ctx) object for a Starlark repository rule ({@code rule}
   * argument).
   */
  StarlarkAdapterContext(
      Rule rule,
      PathPackageLocator packageLocator,
      Path outputDirectory,
      ImmutableSet<PathFragment> ignoredPatterns,
      Environment environment,
      ImmutableMap<String, String> env,
      DownloadManager downloadManager,
      double timeoutScaling,
      @Nullable ProcessWrapper processWrapper,
      StarlarkSemantics starlarkSemantics,
      @Nullable RepositoryRemoteExecutor remoteExecutor,
      SyscallCache syscallCache,
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
    this.repoName = RepositoryName.createUnvalidated(rule.getName());
    this.packageLocator = packageLocator;
    this.ignoredPatterns = ignoredPatterns;
    this.syscallCache = syscallCache;
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
    return RepositoryFetchProgress.repositoryFetchContextString(repoName);
  }

  @StarlarkMethod(
      name = "name",
      structField = true,
      doc = "The name of the external repository created by this rule.")
  public String getName() {
    return rule.getName();
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

  private StarlarkPath externalPath(String method, Object pathObject)
      throws EvalException, InterruptedException {
    StarlarkPath starlarkPath = getPath(method, pathObject);
    Path path = starlarkPath.getPath();
    if (packageLocator.getPathEntries().stream().noneMatch(root -> path.startsWith(root.asPath()))
        || path.startsWith(workingDirectory)) {
      return starlarkPath;
    }
    Path workspaceRoot = packageLocator.getWorkspaceFile(syscallCache).getParentDirectory();
    PathFragment relativePath = path.relativeTo(workspaceRoot);
    for (PathFragment ignoredPattern : ignoredPatterns) {
      if (relativePath.startsWith(ignoredPattern)) {
        return starlarkPath;
      }
    }
    throw Starlark.errorf(
        "%s can only be applied to external paths (that is, outside the workspace or ignored in"
            + " .bazelignore)",
        method);
  }

  @StarlarkMethod(
      name = "symlink",
      doc = "Creates a symlink on the filesystem.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "target",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "The path that the symlink should point to."),
        @Param(
            name = "link_name",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "The path of the symlink to create, relative to the repository directory."),
      })
  public void symlink(Object target, Object linkName, StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath targetPath = getPath("symlink()", target);
    StarlarkPath linkPath = getPath("symlink()", linkName);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newSymlinkEvent(
            targetPath.toString(),
            linkPath.toString(),
            getIdentifyingStringForLogging(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", linkPath);
      makeDirectories(linkPath.getPath());
      linkPath.getPath().createSymbolicLink(targetPath.getPath());
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Could not create symlink from "
                  + targetPath
                  + " to "
                  + linkPath
                  + ": "
                  + e.getMessage(),
              e),
          Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", linkPath, e.getMessage()),
          Transience.PERSISTENT);
    }
  }

  @StarlarkMethod(
      name = "template",
      doc =
          "Generates a new file using a <code>template</code>. Every occurrence in "
              + "<code>template</code> of a key of <code>substitutions</code> will be replaced by "
              + "the corresponding value. The result is written in <code>path</code>. An optional"
              + "<code>executable</code> argument (default to true) can be set to turn on or off"
              + "the executable bit.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "path of the file to create, relative to the repository directory."),
        @Param(
            name = "template",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc = "path to the template file."),
        @Param(
            name = "substitutions",
            defaultValue = "{}",
            named = true,
            doc = "substitutions to make when expanding the template."),
        @Param(
            name = "executable",
            defaultValue = "True",
            named = true,
            doc = "set the executable flag on the created file, true by default."),
      })
  public void createFileFromTemplate(
      Object path,
      Object template,
      Dict<?, ?> substitutions, // <String, String> expected
      Boolean executable,
      StarlarkThread thread)
      throws RepositoryFunctionException, EvalException, InterruptedException {
    StarlarkPath p = getPath("template()", path);
    StarlarkPath t = getPath("template()", template);
    Map<String, String> substitutionMap =
        Dict.cast(substitutions, String.class, String.class, "substitutions");
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newTemplateEvent(
            p.toString(),
            t.toString(),
            substitutionMap,
            executable,
            getIdentifyingStringForLogging(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      checkInOutputDirectory("write", p);
      makeDirectories(p.getPath());
      String tpl = FileSystemUtils.readContent(t.getPath(), StandardCharsets.UTF_8);
      for (Map.Entry<String, String> substitution : substitutionMap.entrySet()) {
        tpl =
            StringUtilities.replaceAllLiteral(tpl, substitution.getKey(), substitution.getValue());
      }
      p.getPath().delete();
      try (OutputStream stream = p.getPath().getOutputStream()) {
        stream.write(tpl.getBytes(StandardCharsets.UTF_8));
      }
      if (executable) {
        p.getPath().setExecutable(true);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    } catch (InvalidPathException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Could not create %s: %s", p, e.getMessage()), Transience.PERSISTENT);
    }
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

  @StarlarkMethod(
      name = "delete",
      doc =
          "Deletes a file or a directory. Returns a bool, indicating whether the file or directory"
              + " was actually deleted by this call.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "path",
            allowedTypes = {@ParamType(type = String.class), @ParamType(type = StarlarkPath.class)},
            doc =
                "Path of the file to delete, relative to the repository directory, or absolute."
                    + " Can be a path or a string."),
      })
  public boolean delete(Object pathObject, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    StarlarkPath starlarkPath = externalPath("delete()", pathObject);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newDeleteEvent(
            starlarkPath.toString(), getIdentifyingStringForLogging(), thread.getCallerLocation());
    env.getListener().post(w);
    try {
      Path path = starlarkPath.getPath();
      path.deleteTreesBelow();
      return path.delete();
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @StarlarkMethod(
      name = "patch",
      doc =
          "Apply a patch file to the root directory of external repository. "
              + "The patch file should be a standard "
              + "<a href=\"https://en.wikipedia.org/wiki/Diff#Unified_format\">"
              + "unified diff format</a> file. "
              + "The Bazel-native patch implementation doesn't support fuzz match and binary patch "
              + "like the patch command line tool.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "patch_file",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            doc =
                "The patch file to apply, it can be label, relative path or absolute path. "
                    + "If it's a relative path, it will resolve to the repository directory."),
        @Param(
            name = "strip",
            named = true,
            defaultValue = "0",
            doc = "strip the specified number of leading components from file names."),
      })
  public void patch(Object patchFile, StarlarkInt stripI, StarlarkThread thread)
      throws EvalException, RepositoryFunctionException, InterruptedException {
    int strip = Starlark.toInt(stripI, "strip");
    StarlarkPath starlarkPath = getPath("patch()", patchFile);
    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newPatchEvent(
            starlarkPath.toString(),
            strip,
            getIdentifyingStringForLogging(),
            thread.getCallerLocation());
    env.getListener().post(w);
    try {
      PatchUtil.apply(starlarkPath.getPath(), strip, workingDirectory);
    } catch (PatchFailedException e) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Error applying patch %s: %s", starlarkPath, e.getMessage()),
          Transience.TRANSIENT);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @StarlarkMethod(
      name = "extract",
      doc = "Extract an archive to the repository directory.",
      useStarlarkThread = true,
      parameters = {
        @Param(
            name = "archive",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            named = true,
            doc =
                "path to the archive that will be unpacked,"
                    + " relative to the repository directory."),
        @Param(
            name = "output",
            allowedTypes = {
              @ParamType(type = String.class),
              @ParamType(type = Label.class),
              @ParamType(type = StarlarkPath.class)
            },
            defaultValue = "''",
            named = true,
            doc =
                "path to the directory where the archive will be unpacked,"
                    + " relative to the repository directory."),
        @Param(
            name = "stripPrefix",
            defaultValue = "''",
            named = true,
            doc =
                "a directory prefix to strip from the extracted files."
                    + "\nMany archives contain a top-level directory that contains all files in the"
                    + " archive. Instead of needing to specify this prefix over and over in the"
                    + " <code>build_file</code>, this field can be used to strip it from extracted"
                    + " files."),
        @Param(
            name = "rename_files",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc =
                "An optional dict specifying files to rename during the extraction. Archive entries"
                    + " with names exactly matching a key will be renamed to the value, prior to"
                    + " any directory prefix adjustment. This can be used to extract archives that"
                    + " contain non-Unicode filenames, or which have files that would extract to"
                    + " the same path on case-insensitive filesystems."),
      })
  public void extract(
      Object archive,
      Object output,
      String stripPrefix,
      Dict<?, ?> renameFiles, // <String, String> expected
      StarlarkThread thread)
      throws RepositoryFunctionException, InterruptedException, EvalException {
    StarlarkPath archivePath = getPath("extract()", archive);

    if (!archivePath.exists()) {
      throw new RepositoryFunctionException(
          Starlark.errorf("Archive path '%s' does not exist.", archivePath), Transience.TRANSIENT);
    }

    StarlarkPath outputPath = getPath("extract()", output);
    checkInOutputDirectory("write", outputPath);

    Map<String, String> renameFilesMap =
        Dict.cast(renameFiles, String.class, String.class, "rename_files");

    WorkspaceRuleEvent w =
        WorkspaceRuleEvent.newExtractEvent(
            archive.toString(),
            output.toString(),
            stripPrefix,
            renameFilesMap,
            getIdentifyingStringForLogging(),
            thread.getCallerLocation());
    env.getListener().post(w);

    env.getListener()
        .post(
            new ExtractProgress(
                outputPath.getPath().toString(), "Extracting " + archivePath.getBasename()));
    DecompressorValue.decompress(
        DecompressorDescriptor.builder()
            .setContext(getIdentifyingStringForLogging())
            .setArchivePath(archivePath.getPath())
            .setDestinationPath(outputPath.getPath())
            .setPrefix(stripPrefix)
            .setRenameFiles(renameFilesMap)
            .build());
    env.getListener().post(new ExtractProgress(outputPath.getPath().toString()));
  }

  @Override
  public String toString() {
    return "repository_ctx[" + rule.getLabel() + "]";
  }

  /**
   * Try to compute the paths of all attributes that are labels, including labels in list and dict
   * arguments.
   *
   * <p>The value is ignored, but any missing information from the environment is detected (and an
   * exception thrown). In this way, we can enforce that all arguments are evaluated before we start
   * potentially more expensive operations.
   */
  // TODO(wyv): somehow migrate this to the base context too.
  public void enforceLabelAttributes() throws EvalException, InterruptedException {
    StructImpl attr = getAttr();
    for (String name : attr.getFieldNames()) {
      Object value = attr.getValue(name);
      if (value instanceof Label) {
        getPathFromLabel((Label) value);
      }
      if (value instanceof Sequence) {
        for (Object entry : (Sequence) value) {
          if (entry instanceof Label) {
            getPathFromLabel((Label) entry);
          }
        }
      }
      if (value instanceof Dict) {
        for (Object entry : ((Dict) value).keySet()) {
          if (entry instanceof Label) {
            getPathFromLabel((Label) entry);
          }
        }
      }
    }
  }
}
