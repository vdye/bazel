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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.skyframe.PackageLookupValue.ErrorReason.NO_BUILD_FILE;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.google.devtools.build.lib.io.FileSymlinkException;
import com.google.devtools.build.lib.io.InconsistentFilesystemException;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.CachingPackageLocator;
import com.google.devtools.build.lib.packages.Globber;
import com.google.devtools.build.lib.packages.GlobberUtils;
import com.google.devtools.build.lib.packages.InvalidPackageNameException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NonSkyframeGlobber;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Package.ConfigSettingVisibilityPolicy;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.PackageValidator.InvalidPackageException;
import com.google.devtools.build.lib.packages.RuleVisibility;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading;
import com.google.devtools.build.lib.server.FailureDetails.PackageLoading.Code;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.GlobValue.InvalidGlobPatternException;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsFunction.BuiltinsFailedException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.UnixGlob;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;
import org.apache.commons.lang.NotImplementedException;

/** A SkyFunction for {@link PackageValue}s. */
public class PackageFunction implements SkyFunction {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final PackageFactory packageFactory;
  private final CachingPackageLocator packageLocator;
  private final AtomicBoolean showLoadingProgress;
  private final AtomicInteger numPackagesSuccessfullyLoaded;
  @Nullable private final PackageProgressReceiver packageProgress;

  // Not final only for testing.
  @Nullable private BzlLoadFunction bzlLoadFunctionForInlining;

  private final ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile;

  private final GlobbingStrategy globbingStrategy;

  private final Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics;

  public PackageFunction(
      PackageFactory packageFactory,
      CachingPackageLocator pkgLocator,
      AtomicBoolean showLoadingProgress,
      AtomicInteger numPackagesSuccessfullyLoaded,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining,
      @Nullable PackageProgressReceiver packageProgress,
      ActionOnIOExceptionReadingBuildFile actionOnIOExceptionReadingBuildFile,
      GlobbingStrategy globbingStrategy,
      Function<SkyKey, ThreadStateReceiver> threadStateReceiverFactoryForMetrics) {
    this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
    this.packageFactory = packageFactory;
    this.packageLocator = pkgLocator;
    this.showLoadingProgress = showLoadingProgress;
    this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded;
    this.packageProgress = packageProgress;
    this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile;
    this.globbingStrategy = globbingStrategy;
    this.threadStateReceiverFactoryForMetrics = threadStateReceiverFactoryForMetrics;
  }

  public void setBzlLoadFunctionForInliningForTesting(BzlLoadFunction bzlLoadFunctionForInlining) {
    this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining;
  }

  /**
   * What to do when encountering an {@link IOException} trying to read the contents of a BUILD
   * file.
   *
   * <p>Any choice besides {@link
   * ActionOnIOExceptionReadingBuildFile.UseOriginalIOException#INSTANCE} is potentially
   * incrementally unsound: if the initial {@link IOException} is transient, then Blaze will
   * "incorrectly" not attempt to redo package loading for this BUILD file on incremental builds.
   *
   * <p>The fact that this behavior is configurable and potentially unsound is a concession to
   * certain desired use cases with fancy filesystems.
   */
  public interface ActionOnIOExceptionReadingBuildFile {
    /**
     * Given the {@link IOException} encountered when reading the contents of the given BUILD file,
     * returns the contents that should be used, or {@code null} if the original {@link IOException}
     * should be respected (that is, we should error-out with a package loading error).
     */
    @Nullable
    byte[] maybeGetBuildFileContentsToUse(
        PathFragment buildFilePathFragment, IOException originalExn);

    /**
     * A {@link ActionOnIOExceptionReadingBuildFile} whose {@link #maybeGetBuildFileContentsToUse}
     * has the sensible behavior of always respecting the initial {@link IOException}.
     */
    public static class UseOriginalIOException implements ActionOnIOExceptionReadingBuildFile {
      public static final UseOriginalIOException INSTANCE = new UseOriginalIOException();

      private UseOriginalIOException() {}

      @Override
      @Nullable
      public byte[] maybeGetBuildFileContentsToUse(
          PathFragment buildFilePathFragment, IOException originalExn) {
        return null;
      }
    }
  }

  /** Ways that {@link PackageFunction} can perform globbing. */
  public enum GlobbingStrategy {
    /**
     * Globs are resolved using {@link SkyframeHybridGlobber}, which declares proper Skyframe
     * dependencies.
     *
     * <p>Use when {@link PackageFunction} will be used to load packages incrementally (e.g. on both
     * clean builds and incremental builds, perhaps with cached globs). This is Bazel's normal
     * use-case.
     */
    SKYFRAME_HYBRID,

    /**
     * Globs are resolved using {@link NonSkyframeGlobber}, which does not declare Skyframe
     * dependencies.
     *
     * <p>This is a performance optimization only for use when {@link PackageFunction} will never be
     * used to load packages incrementally. Do not use this unless you know what you are doing;
     * Bazel will be intentionally incrementally incorrect!
     */
    NON_SKYFRAME
  }

  private static void maybeThrowFilesystemInconsistency(
      PackageIdentifier pkgId, Exception skyframeException, boolean packageWasInError)
      throws InternalInconsistentFilesystemException {
    if (!packageWasInError) {
      throw new InternalInconsistentFilesystemException(
          pkgId,
          "Encountered error '"
              + skyframeException.getMessage()
              + "' but didn't encounter it when doing the same thing earlier in the build");
    }
  }

  /**
   * These deps have already been marked (see {@link SkyframeHybridGlobber}) but we need to properly
   * handle symlink issues that {@link NonSkyframeGlobber} can't handle gracefully.
   */
  private static void handleGlobDepsAndPropagateFilesystemExceptions(
      PackageIdentifier packageIdentifier,
      Iterable<SkyKey> depKeys,
      Environment env,
      boolean packageWasInError)
      throws InternalInconsistentFilesystemException, FileSymlinkException, InterruptedException {
    checkState(Iterables.all(depKeys, SkyFunctions.isSkyFunction(SkyFunctions.GLOB)), depKeys);
    FileSymlinkException arbitraryFse = null;
    SkyframeLookupResult result = env.getValuesAndExceptions(depKeys);
    for (SkyKey key : depKeys) {
      try {
        result.getOrThrow(key, IOException.class, BuildFileNotFoundException.class);
      } catch (InconsistentFilesystemException e) {
        throw new InternalInconsistentFilesystemException(packageIdentifier, e);
      } catch (FileSymlinkException e) {
        // Non-Skyframe globbing doesn't explicitly detect symlink issues, but certain filesystems
        // might detect some symlink issues. For example, many filesystems have a hardcoded bound on
        // the number of symlink hops they will follow when resolving paths (e.g. Unix's ELOOP).
        // Since Skyframe globbing does explicitly detect symlink issues, we are able to:
        //   (1) Provide a more informative error message.
        //   (2) Confidently act as though the symlink issue is non-transient.
        arbitraryFse = e;
      } catch (IOException | BuildFileNotFoundException e) {
        maybeThrowFilesystemInconsistency(packageIdentifier, e, packageWasInError);
      }
    }
    if (arbitraryFse != null) {
      // If there was at least one symlink issue and no inconsistent filesystem issues, arbitrarily
      // rethrow one of the symlink issues.
      throw arbitraryFse;
    }
  }

  /**
   * Adds a dependency on the WORKSPACE file, representing it as a special type of package.
   *
   * @throws PackageFunctionException if there is an error computing the workspace file or adding
   *     its rules to the //external package.
   */
  @Nullable
  private SkyValue getExternalPackage(Environment env)
      throws PackageFunctionException, InterruptedException {
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }

    SkyKey workspaceKey = ExternalPackageFunction.key();
    PackageValue workspace = null;
    try {
      // This may throw a NoSuchPackageException if the WORKSPACE file was malformed or had other
      // problems. Since this function can't add much context, we silently bubble it up.
      workspace =
          (PackageValue)
              env.getValueOrThrow(
                  workspaceKey,
                  IOException.class,
                  EvalException.class,
                  BzlLoadFailedException.class);
    } catch (IOException | EvalException | BzlLoadFailedException e) {
      String message = "Error encountered while dealing with the WORKSPACE file: " + e.getMessage();
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)
          .setMessage(message)
          .setPackageLoadingCode(PackageLoading.Code.WORKSPACE_FILE_ERROR)
          .build();
    }
    if (workspace == null) {
      return null;
    }

    Package pkg = workspace.getPackage();
    if (packageFactory != null) {
      try {
        packageFactory.afterDoneLoadingPackage(
            pkg,
            starlarkSemantics,
            // This is a lie.
            /*loadTimeNanos=*/ 0L,
            env.getListener());
      } catch (InvalidPackageException e) {
        throw new PackageFunctionException(e, Transience.PERSISTENT);
      }
    }
    if (!pkg.containsErrors()) {
      numPackagesSuccessfullyLoaded.incrementAndGet();
    }
    return new PackageValue(pkg);
  }

  private static class LoadedPackage {
    private final Package.Builder builder;
    private final Set<SkyKey> globDepKeys;
    private final long loadTimeNanos;

    private LoadedPackage(Package.Builder builder, Set<SkyKey> globDepKeys, long loadTimeNanos) {
      this.builder = builder;
      this.globDepKeys = globDepKeys;
      this.loadTimeNanos = loadTimeNanos;
    }
  }

  private static class State implements SkyKeyComputeState {
    @Nullable private CompiledBuildFile compiledBuildFile;
    @Nullable private LoadedPackage loadedPackage;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws PackageFunctionException, InterruptedException {
    PackageIdentifier packageId = (PackageIdentifier) key.argument();
    if (packageId.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
      return getExternalPackage(env);
    }

    // TODO: use a different key for PackageLookupFunction (wrapper for PackageIdentifier - PackageLookupIdentifier)
    SkyKey packageLookupKey = PackageLookupValue.key(PackageIdentifier.create(packageId.getRepository(), packageId.getPackageFragment(), packageId.getRepository().isMain()));
    PackageLookupValue packageLookupValue;
    try {
      packageLookupValue =
          (PackageLookupValue)
              env.getValueOrThrow(
                  packageLookupKey,
                  BuildFileNotFoundException.class,
                  InconsistentFilesystemException.class);
    } catch (BuildFileNotFoundException e) {
      throw new PackageFunctionException(e, Transience.PERSISTENT);
    } catch (InconsistentFilesystemException e) {
      // This error is not transient from the perspective of the PackageFunction.
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(packageId)
          .setMessage(e.getMessage())
          .setException(e)
          .setPackageLoadingCode(PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR)
          .build();
    }
    if (packageLookupValue == null) {
      return null;
    }

    if (!packageLookupValue.packageExists()) {
      PackageFunctionException.Builder exceptionBuilder =
          PackageFunctionException.builder()
              .setPackageIdentifier(packageId)
              .setTransience(Transience.PERSISTENT);
      switch (packageLookupValue.getErrorReason()) {
        case NO_BUILD_FILE:
          String message = PackageLookupFunction.explainNoBuildFileValue(packageId, env);
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
              .setMessage(message)
              .setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
              .build();
        case DELETED_PACKAGE:
        case REPOSITORY_NOT_FOUND:
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
              .setMessage(packageLookupValue.getErrorMsg())
              .setPackageLoadingCode(PackageLoading.Code.REPOSITORY_MISSING)
              .build();
        case INVALID_PACKAGE_NAME:
          throw exceptionBuilder
              .setType(PackageFunctionException.Type.INVALID_PACKAGE_NAME)
              .setMessage(packageLookupValue.getErrorMsg())
              .setPackageLoadingCode(PackageLoading.Code.INVALID_NAME)
              .build();
      }
      // We should never get here.
      throw new IllegalStateException();
    }

    StarlarkBuiltinsValue starlarkBuiltinsValue;
    try {
      if (bzlLoadFunctionForInlining == null) {
        starlarkBuiltinsValue =
            (StarlarkBuiltinsValue)
                env.getValueOrThrow(StarlarkBuiltinsValue.key(), BuiltinsFailedException.class);
      } else {
        starlarkBuiltinsValue =
            StarlarkBuiltinsFunction.computeInline(
                StarlarkBuiltinsValue.key(),
                BzlLoadFunction.InliningState.create(env),
                packageFactory,
                bzlLoadFunctionForInlining);
      }
    } catch (BuiltinsFailedException e) {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setTransience(Transience.PERSISTENT)
          .setMessage(
              String.format("Internal error while loading Starlark builtins: %s", e.getMessage()))
          .setPackageLoadingCode(PackageLoading.Code.BUILTINS_INJECTION_FAILURE)
          .build();
    }

    if (env.valuesMissing()) {
      return null;
    }

    // TODO(adonovan): put BUILD compilation from BUILD execution in separate Skyframe functions
    // like we do for .bzl files, so that we don't need to recompile BUILD files each time their
    // .bzl dependencies change.

    State state = env.getState(State::new);
    if (state.loadedPackage == null) {
      state.loadedPackage =
          loadPackage(
              packageLookupValue,
              packageId,
              starlarkBuiltinsValue,
              packageLookupValue.getRoot(),
              env,
              key,
              state);
      if (state.loadedPackage == null) {
        return null;
      }
    }
    PackageFunctionException pfeFromNonSkyframeGlobbing = null;
    Package.Builder pkgBuilder = state.loadedPackage.builder;
    try {
      pkgBuilder.buildPartial();
      // Since the Skyframe dependencies we request below in
      // handleGlobDepsAndPropagateFilesystemExceptions are requested independently of the ones
      // requested here in
      // handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions, we don't
      // bother checking for missing values and instead piggyback on the env.missingValues() call
      // for the former. This avoids a Skyframe restart.
      // Note that handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
      // expects to mutate pkgBuilder.getTargets(), and thus can only be safely called if
      // pkgBuilder.buildPartial() didn't throw.
      handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
          packageLookupValue.getRoot(), packageId, pkgBuilder, env);
    } catch (NoSuchPackageException e) {
      // If non-Skyframe globbing encounters an IOException, #buildPartial will throw a
      // NoSuchPackageException. If that happens, we prefer throwing an exception derived from
      // Skyframe globbing. See the comments in #handleGlobDepsAndPropagateFilesystemExceptions.
      // Therefore we store the exception encountered here and maybe use it later.
      pfeFromNonSkyframeGlobbing =
          new PackageFunctionException(
              e,
              e.getCause() instanceof SkyframeGlobbingIOException
                  ? Transience.PERSISTENT
                  : Transience.TRANSIENT);
    } catch (InternalInconsistentFilesystemException e) {
      throw e.throwPackageFunctionException();
    }
    Set<SkyKey> globKeys = state.loadedPackage.globDepKeys;
    try {
      handleGlobDepsAndPropagateFilesystemExceptions(
          packageId, globKeys, env, pkgBuilder.containsErrors());
    } catch (InternalInconsistentFilesystemException e) {
      throw e.throwPackageFunctionException();
    } catch (FileSymlinkException e) {
      String message = "Symlink issue while evaluating globs: " + e.getUserFriendlyMessage();
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setTransience(Transience.PERSISTENT)
          .setPackageIdentifier(packageId)
          .setMessage(message)
          .setPackageLoadingCode(PackageLoading.Code.EVAL_GLOBS_SYMLINK_ERROR)
          .build();
    }

    if (pfeFromNonSkyframeGlobbing != null) {
      // Throw before checking for missing values, since this may be our last chance to throw if in
      // nokeep-going error bubbling.
      throw pfeFromNonSkyframeGlobbing;
    }

    if (env.valuesMissing()) {
      return null;
    }

    Package pkg = pkgBuilder.finishBuild();

    Event.replayEventsOn(env.getListener(), pkgBuilder.getEvents());
    for (Postable post : pkgBuilder.getPosts()) {
      env.getListener().post(post);
    }

    try {
      packageFactory.afterDoneLoadingPackage(
          pkg,
          starlarkBuiltinsValue.starlarkSemantics,
          state.loadedPackage.loadTimeNanos,
          env.getListener());
    } catch (InvalidPackageException e) {
      throw new PackageFunctionException(e, Transience.PERSISTENT);
    }
    if (!pkg.containsErrors()) {
      numPackagesSuccessfullyLoaded.incrementAndGet();
    }
    return new PackageValue(pkg);
  }

  @Nullable
  private static FileValue getBuildFileValue(Environment env, RootedPath buildFileRootedPath)
      throws InterruptedException {
    FileValue buildFileValue;
    try {
      buildFileValue =
          (FileValue) env.getValueOrThrow(FileValue.key(buildFileRootedPath), IOException.class);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Package lookup succeeded but encountered error when "
              + "getting FileValue for BUILD file directly.",
          e);
    }
    if (buildFileValue == null) {
      return null;
    }
    checkState(buildFileValue.exists(), "Package lookup succeeded but BUILD file doesn't exist");
    return buildFileValue;
  }

  @Nullable
  private static FileValue getVirtualBuildFileValue(Environment env, RootedPath buildFileRootedPath)
          throws InterruptedException {
    FileValue buildFileValue;
    try {
      buildFileValue =
              (FileValue) env.getValueOrThrow(FileValue.virtualKey(buildFileRootedPath), IOException.class);
    } catch (IOException e) {
      throw new IllegalStateException(
              "Package lookup succeeded but encountered error when "
                      + "getting FileValue for BUILD file directly.",
              e);
    }
    if (buildFileValue == null) {
      return null;
    }
    checkState(buildFileValue.exists(), "Package lookup succeeded but BUILD file doesn't exist");
    return buildFileValue;
  }

  /**
   * Loads the .bzl modules whose names and load-locations are {@code programLoads}, and whose
   * corresponding Skyframe keys are {@code keys}.
   *
   * <p>Validates load visibility for loaded modules.
   *
   * <p>Returns a map from module name to module, or null for a Skyframe restart.
   *
   * <p>The {@code packageId} is used only for error reporting.
   *
   * <p>This function is called for load statements in BUILD and WORKSPACE files. For loads in .bzl
   * files, see {@link BzlLoadFunction}.
   */
  /*
   * TODO(b/237658764): This logic has several problems:
   *
   * - It is partly duplicated by loadPrelude() below.
   * - The meaty computeBzlLoads* helpers are almost copies of BzlLoadFunction#computeBzlLoads*.
   * - This function is called from WorkspaceFileFunction and BzlmodRepoRuleFunction (and morally
   *   probably should be called by SingleExtensionEvalFunction rather than requesting a BzlLoadKey
   *   directly). But the API is awkward for these callers.
   * - InliningState is not shared across all callers within a BUILD file; see the comment in
   *   computeBzlLoadsWithInlining.
   *
   * To address these issues, we can instead make public the two BzlLoadFunction#computeBzlLoads*
   * methods. Their programLoads parameter is only used for wrapping exceptions in
   * BzlLoadFailedException#whileLoadingDep, but we can probably push that wrapping to the caller.
   * If we fix PackageFunction to use a shared InliningState, then our computeBzlLoadsWithInlining
   * method will take it as a param and its signature will then basically match the one in
   * BzlLoadFunction.
   *
   * At that point we can eliminate our own computeBzlLoads* methods in favor of the BzlLoadFunction
   * ones. We could factor out the piece of loadBzlModules that dispatches to computeBzlLoads* and
   * translates the possible exception, and push the visibility checking and loadedModules map
   * construction to the caller, so that loadPrelude can become just a call to the factored-out
   * code.
   */
  @Nullable
  static ImmutableMap<String, Module> loadBzlModules(
      Environment env,
      PackageIdentifier packageId,
      String requestingFileDescription,
      List<Pair<String, Location>> programLoads,
      List<BzlLoadValue.Key> keys,
      StarlarkSemantics semantics,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining)
      throws NoSuchPackageException, InterruptedException {
    List<BzlLoadValue> bzlLoads;
    try {
      bzlLoads =
          bzlLoadFunctionForInlining == null
              ? computeBzlLoadsNoInlining(env, keys)
              : computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining);
      if (bzlLoads == null) {
        return null; // Skyframe deps unavailable
      }
      // Validate that the current BUILD/WORKSPACE file satisfies each loaded dependency's
      // load visibility.
      BzlLoadFunction.checkLoadVisibilities(
          packageId,
          requestingFileDescription,
          bzlLoads,
          keys,
          programLoads,
          /*demoteErrorsToWarnings=*/ !semantics.getBool(BuildLanguageOptions.CHECK_BZL_VISIBILITY),
          env.getListener());
    } catch (BzlLoadFailedException e) {
      Throwable rootCause = Throwables.getRootCause(e);
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setException(rootCause instanceof IOException ? (IOException) rootCause : null)
          .setMessage(e.getMessage())
          .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
          .buildCause();
    }

    // Build map of loaded modules.
    Map<String, Module> loadedModules = Maps.newLinkedHashMapWithExpectedSize(bzlLoads.size());
    for (int i = 0; i < bzlLoads.size(); i++) {
      loadedModules.put(programLoads.get(i).first, bzlLoads.get(i).getModule()); // dups ok
    }
    return ImmutableMap.copyOf(loadedModules);
  }

  // Loads the prelude identified by the label. Returns null for a skyframe restart.
  @Nullable
  private static Module loadPrelude(
      Environment env,
      PackageIdentifier packageId,
      Label label,
      @Nullable BzlLoadFunction bzlLoadFunctionForInlining)
      throws NoSuchPackageException, InterruptedException {
    List<BzlLoadValue.Key> keys = ImmutableList.of(BzlLoadValue.keyForBuildPrelude(label));
    try {
      List<BzlLoadValue> loads =
          bzlLoadFunctionForInlining == null
              ? computeBzlLoadsNoInlining(env, keys)
              : computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining);
      if (loads == null) {
        return null; // skyframe restart
      }
      // No need to validate visibility since we're processing an internal load on behalf of Bazel.
      return loads.get(0).getModule();

    } catch (BzlLoadFailedException e) {
      Throwable rootCause = Throwables.getRootCause(e);
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
          .setPackageIdentifier(packageId)
          .setException(rootCause instanceof IOException ? (IOException) rootCause : null)
          .setMessage(e.getMessage())
          .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
          .buildCause();
    }
  }

  /**
   * Compute the BzlLoadValue for all given keys using vanilla Skyframe evaluation, returning {@code
   * null} if Skyframe deps were missing and have been requested.
   */
  @Nullable
  private static List<BzlLoadValue> computeBzlLoadsNoInlining(
      Environment env, List<BzlLoadValue.Key> keys)
      throws InterruptedException, BzlLoadFailedException {
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    SkyframeLookupResult starlarkLookupResults = env.getValuesAndExceptions(keys);
    for (BzlLoadValue.Key key : keys) {
      // TODO(adonovan): if get fails, report the source location
      // in the corresponding programLoads[i] (see caller).
      bzlLoads.add(
          (BzlLoadValue) starlarkLookupResults.getOrThrow(key, BzlLoadFailedException.class));
    }
    return env.valuesMissing() ? null : bzlLoads;
  }

  /**
   * Compute the BzlLoadValue for all given keys by "inlining" the BzlLoadFunction and bypassing
   * traditional Skyframe evaluation, returning {@code null} if Skyframe deps were missing and have
   * been requested.
   */
  @Nullable
  private static List<BzlLoadValue> computeBzlLoadsWithInlining(
      Environment env, List<BzlLoadValue.Key> keys, BzlLoadFunction bzlLoadFunctionForInlining)
      throws InterruptedException, BzlLoadFailedException {
    List<BzlLoadValue> bzlLoads = Lists.newArrayListWithExpectedSize(keys.size());
    // See the comment about the desire for deterministic graph structure in BzlLoadFunction for the
    // motivation of this approach to exception handling.
    BzlLoadFailedException deferredException = null;
    // Compute BzlLoadValue for each key, sharing the same inlining state, i.e. cache of loaded
    // modules. This ensures that each .bzl is loaded only once, regardless of diamond dependencies
    // or cache eviction. (Multiple loads of the same .bzl would screw up identity equality of some
    // Starlark symbols -- see comments in BzlLoadFunction#computeInline.)
    // TODO(brandjon): Note that using a fresh InliningState in each call to this function means
    // that we don't get sharing between the top-level callers -- namely, the callers that retrieve
    // the BUILD file's loads, the prelude file, and the @_builtins. Since there's still a global
    // cache of bzls, this is only really a problem if the same bzl can appear in more than one of
    // those contexts. This *can* happen if a dependency of the prelude file is also reachable
    // through regular loads, but *only* in OSS Bazel, where inlining is not really used. The fix
    // would be to thread a single InliningState through all call sites within the same call to
    // compute().
    BzlLoadFunction.InliningState inliningState = BzlLoadFunction.InliningState.create(env);
    for (BzlLoadValue.Key key : keys) {
      SkyValue skyValue;
      try {
        // Will complete right away if this key has been seen before in inliningState -- regardless
        // of whether it was evaluated successfully, had missing deps, or was found to be in error.
        skyValue = bzlLoadFunctionForInlining.computeInline(key, inliningState);
      } catch (BzlLoadFailedException e) {
        if (deferredException == null) {
          deferredException = e;
        }
        continue;
      }
      if (skyValue != null) {
        bzlLoads.add((BzlLoadValue) skyValue);
      }
      // A null value for `skyValue` can occur when it (or its transitive loads) has a Skyframe dep
      // that is missing or in error. It can also occur if there's a transitive load on a bzl that
      // was already seen by inliningState and which returned null. In both these cases, we want to
      // continue making our inline calls, so as to maximize the number of dependent (non-inlined)
      // SkyFunctions that are requested and avoid a quadratic number of restarts.
    }
    if (deferredException != null) {
      throw deferredException;
    }
    return env.valuesMissing() ? null : bzlLoads;
  }

  /**
   * For each of a {@link Package.Builder}'s targets, propagate the target's corresponding {@link
   * InconsistentFilesystemException} (if any) and verify that the target's label does not cross
   * subpackage boundaries.
   *
   * @param pkgBuilder a {@link Package.Builder} whose {@code getTargets()} set is mutable (i.e.
   *     {@code pkgBuilder.buildPartial()} must have been successfully called).
   */
  private static void handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
      Root pkgRoot, PackageIdentifier pkgId, Package.Builder pkgBuilder, Environment env)
      throws InternalInconsistentFilesystemException, InterruptedException {
    PathFragment pkgDir = pkgId.getPackageFragment();
    Set<SkyKey> containingPkgLookupKeys = Sets.newHashSet();
    Map<Target, SkyKey> targetToKey = new HashMap<>();
    for (Target target : pkgBuilder.getTargets()) {
      PathFragment dir = Label.getContainingDirectory(target.getLabel());
      if (dir.equals(pkgDir)) {
        continue;
      }
      PackageIdentifier dirId = PackageIdentifier.create(pkgId.getRepository(), dir);
      SkyKey key = ContainingPackageLookupValue.key(dirId);
      targetToKey.put(target, key);
      containingPkgLookupKeys.add(key);
    }
    SkyframeLookupResult containingPkgLookupValues =
        env.getValuesAndExceptions(containingPkgLookupKeys);
    if (env.valuesMissing() || containingPkgLookupKeys.isEmpty()) {
      return;
    }
    for (Iterator<Target> iterator = pkgBuilder.getTargets().iterator(); iterator.hasNext(); ) {
      Target target = iterator.next();
      SkyKey key = targetToKey.get(target);
      if (!containingPkgLookupKeys.contains(key)) {
        continue;
      }
      ContainingPackageLookupValue containingPackageLookupValue;
      try {
        containingPackageLookupValue =
            (ContainingPackageLookupValue)
                containingPkgLookupValues.getOrThrow(
                    key, BuildFileNotFoundException.class, InconsistentFilesystemException.class);
      } catch (BuildFileNotFoundException e) {
        env.getListener().handle(Event.error(null, e.getMessage()));
        containingPackageLookupValue = null;
      } catch (InconsistentFilesystemException e) {
        throw new InternalInconsistentFilesystemException(pkgId, e);
      }
      if (maybeAddEventAboutLabelCrossingSubpackage(
          pkgBuilder,
          pkgRoot,
          target.getLabel(),
          target.getLocation(),
          containingPackageLookupValue)) {
        iterator.remove();
        pkgBuilder.setContainsErrors();
      }
    }
  }

  private static boolean maybeAddEventAboutLabelCrossingSubpackage(
      Package.Builder pkgBuilder,
      Root pkgRoot,
      Label label,
      @Nullable Location location,
      @Nullable ContainingPackageLookupValue containingPkgLookupValue) {
    if (containingPkgLookupValue == null) {
      return true;
    }
    if (!containingPkgLookupValue.hasContainingPackage()) {
      // The missing package here is a problem, but it's not an error from the perspective of
      // PackageFunction.
      return false;
    }
    PackageIdentifier containingPkg = containingPkgLookupValue.getContainingPackageName();
    if (containingPkg.equals(label.getPackageIdentifier())) {
      // The label does not cross a subpackage boundary.
      return false;
    }
    if (!containingPkg.getSourceRoot().startsWith(label.getPackageIdentifier().getSourceRoot())) {
      // This label is referencing an imaginary package, because the containing package should
      // extend the label's package: if the label is //a/b:c/d, the containing package could be
      // //a/b/c or //a/b, but should never be //a. Usually such errors will be caught earlier, but
      // in some exceptional cases (such as a Python-aware BUILD file catching its own io
      // exceptions), it reaches here, and we tolerate it.
      return false;
    }
    String message =
        ContainingPackageLookupValue.getErrorMessageForLabelCrossingPackageBoundary(
            pkgRoot, label, containingPkgLookupValue);
    pkgBuilder.addEvent(Package.error(location, message, Code.LABEL_CROSSES_PACKAGE_BOUNDARY));
    return true;
  }

  private interface GlobberWithSkyframeGlobDeps extends Globber {
    Set<SkyKey> getGlobDepsRequested();
  }

  private static class NonSkyframeGlobberWithNoGlobDeps implements GlobberWithSkyframeGlobDeps {
    private final NonSkyframeGlobber delegate;

    private NonSkyframeGlobberWithNoGlobDeps(NonSkyframeGlobber delegate) {
      this.delegate = delegate;
    }

    @Override
    public Set<SkyKey> getGlobDepsRequested() {
      return ImmutableSet.of();
    }

    @Override
    public Token runAsync(
        List<String> includes,
        List<String> excludes,
        Globber.Operation globberOperation,
        boolean allowEmpty)
        throws BadGlobException, InterruptedException {
      return delegate.runAsync(includes, excludes, globberOperation, allowEmpty);
    }

    @Override
    public List<String> fetchUnsorted(Token token)
        throws BadGlobException, IOException, InterruptedException {
      return delegate.fetchUnsorted(token);
    }

    @Override
    public void onInterrupt() {
      delegate.onInterrupt();
    }

    @Override
    public void onCompletion() {
      delegate.onCompletion();
    }
  }

  /**
   * A {@link Globber} implemented on top of Skyframe that falls back to a {@link
   * NonSkyframeGlobber} on a Skyframe cache-miss. This way we don't require a Skyframe restart
   * after a call to {@link Globber#runAsync} and before/during a call to {@link
   * Globber#fetchUnsorted}.
   *
   * <p>There are three advantages to this hybrid approach over the more obvious approach of solely
   * using a {@link NonSkyframeGlobber}:
   *
   * <ul>
   *   <li>We trivially have the proper Skyframe {@link GlobValue} deps, whereas we would need to
   *       request them after-the-fact if we solely used a {@link NonSkyframeGlobber}.
   *   <li>We don't need to re-evaluate globs whose expression hasn't changed (e.g. in the common
   *       case of a BUILD file edit that doesn't change a glob expression), whereas invoking the
   *       package loading machinery in {@link PackageFactory} with a {@link NonSkyframeGlobber}
   *       would naively re-evaluate globs when re-evaluating the BUILD file.
   *   <li>We don't need to re-evaluate invalidated globs *twice* (the single re-evaluation via our
   *       GlobValue deps is sufficient and optimal). See above for why the second evaluation would
   *       happen.
   * </ul>
   *
   * <p>One general disadvantage of the hybrid approach is that we do the logical globbing work
   * twice on clean builds. A part of this is that we do double the number of 'stat' filesystem
   * operations: non-Skyframe globbing does `stat` operations following symlinks, and Skyframe's
   * {@link FileStateFunction} does those operations not following symlinks (since {@link
   * FileFunction} handles symlink chains manually). We used to have a similar concern for `readdir`
   * operations, but we mitigated it by restructuring the non-Skyframe globbing code so that it
   * doesn't follow symlinks for these operations, allowing the results to be cached and used by
   * {@link DirectoryListingStateFunction}.
   *
   * <p>This theoretical inefficiency isn't a big deal in practice, and historical attempts to
   * completely remove it by solely using Skyframe's {@link GlobFunction} have been unsuccessful due
   * to the consequences of Skyframe restarts on package loading performance. If we knew the full
   * set of `glob` calls that would be performed during BUILD file evaluation, then we could
   * precompute those {@link GlobValue} nodes and not have any Skyframe restarts during. But that's
   * a big "if"; consider glob calls with non-static arguments:
   *
   * <pre>
   * P = f(42)
   * g(glob(P))
   * </pre>
   *
   * Also consider dependent glob calls:
   *
   * <pre>
   * L = glob(["foo.*"])
   * g(glob([f(x) for x in L])
   * </pre>
   *
   * One historical attempt at addressing this issue was to do a first pass of BUILD file evaluation
   * where we tried to encounter as many concrete glob calls as possible but without doing full
   * Starlark evaluation, and then do the real pass of BUILD file evaluation. This approach was a
   * net performance regression, due to the first pass both having non-trivial Starlark evaluation
   * cost (consider a very expensive function 'f' in the first example above) and also not
   * encountering all glob calls (meaning the real pass can still have the core problem with
   * Skyframe restarts).
   */
  private static class SkyframeHybridGlobber implements GlobberWithSkyframeGlobDeps {
    private final PackageIdentifier packageId;
    private final Root packageRoot;
    private final Environment env;
    private final NonSkyframeGlobber nonSkyframeGlobber;
    private final Set<SkyKey> globDepsRequested = Sets.newConcurrentHashSet();

    private SkyframeHybridGlobber(
        PackageIdentifier packageId,
        Root packageRoot,
        Environment env,
        NonSkyframeGlobber nonSkyframeGlobber) {
      this.packageId = packageId;
      this.packageRoot = packageRoot;
      this.env = env;
      this.nonSkyframeGlobber = nonSkyframeGlobber;
    }

    @Override
    public Set<SkyKey> getGlobDepsRequested() {
      return ImmutableSet.copyOf(globDepsRequested);
    }

    private SkyKey getGlobKey(String pattern, Globber.Operation globberOperation)
        throws BadGlobException {
      try {
        return GlobValue.key(
            packageId, packageRoot, pattern, globberOperation, PathFragment.EMPTY_FRAGMENT);
      } catch (InvalidGlobPatternException e) {
        throw new BadGlobException(e.getMessage());
      }
    }

    @Override
    public Token runAsync(
        List<String> includes,
        List<String> excludes,
        Globber.Operation globberOperation,
        boolean allowEmpty)
        throws BadGlobException, InterruptedException {
      LinkedHashSet<SkyKey> globKeys = Sets.newLinkedHashSetWithExpectedSize(includes.size());
      Map<SkyKey, String> globKeyToPatternMap = Maps.newHashMapWithExpectedSize(includes.size());

      for (String pattern : includes) {
        SkyKey globKey = getGlobKey(pattern, globberOperation);
        globKeys.add(globKey);
        globKeyToPatternMap.put(globKey, pattern);
      }

      globDepsRequested.addAll(globKeys);

      SkyframeLookupResult globValueMap = env.getValuesAndExceptions(globKeys);

      // For each missing glob, evaluate it asynchronously via the delegate.
      List<SkyKey> missingKeys = new ArrayList<>(globKeys.size());
      for (SkyKey globKey : globKeys) {
        try {
          SkyValue value =
              globValueMap.getOrThrow(globKey, IOException.class, BuildFileNotFoundException.class);
          if (value == null) {
            missingKeys.add(globKey);
          }
        } catch (IOException | BuildFileNotFoundException e) {
          logger.atWarning().withCause(e).log("Exception processing %s", globKey);
        }
      }
      List<String> globsToDelegate = new ArrayList<>(missingKeys.size());
      for (SkyKey missingKey : missingKeys) {
        String missingPattern = globKeyToPatternMap.get(missingKey);
        if (missingPattern != null) {
          globsToDelegate.add(missingPattern);
          globKeys.remove(missingKey);
        }
      }
      NonSkyframeGlobber.Token nonSkyframeIncludesToken =
          globsToDelegate.isEmpty()
              ? null
              : nonSkyframeGlobber.runAsync(
                  globsToDelegate, ImmutableList.of(), globberOperation, allowEmpty);
      return new HybridToken(
          globValueMap, globKeys, nonSkyframeIncludesToken, excludes, globberOperation, allowEmpty);
    }

    @Override
    public List<String> fetchUnsorted(Token token)
        throws BadGlobException, IOException, InterruptedException {
      HybridToken hybridToken = (HybridToken) token;
      return hybridToken.resolve(nonSkyframeGlobber);
    }

    @Override
    public void onInterrupt() {
      nonSkyframeGlobber.onInterrupt();
    }

    @Override
    public void onCompletion() {
      nonSkyframeGlobber.onCompletion();
    }

    /**
     * A {@link Globber.Token} that encapsulates the result of a single {@link Globber#runAsync}
     * call via the fetching of some globs from skyframe, and some other globs via a {@link
     * NonSkyframeGlobber}. 'exclude' patterns are evaluated using {@link UnixGlob#removeExcludes}
     * after merging the glob results in {@link #resolve}.
     */
    private static class HybridToken extends Globber.Token {
      // The result of the Skyframe lookup for all the needed glob patterns.
      private final SkyframeLookupResult globValueMap;
      // The skyframe keys corresponding to the 'includes' patterns fetched from Skyframe
      // (this is includes_sky above).
      private final Iterable<SkyKey> includesGlobKeys;
      @Nullable private final NonSkyframeGlobber.Token nonSkyframeGlobberIncludesToken;

      private final List<String> excludes;

      private final Globber.Operation globberOperation;

      private final boolean allowEmpty;

      private HybridToken(
          SkyframeLookupResult globValueMap,
          Iterable<SkyKey> includesGlobKeys,
          @Nullable NonSkyframeGlobber.Token nonSkyframeGlobberIncludesToken,
          List<String> excludes,
          Globber.Operation globberOperation,
          boolean allowEmpty) {
        this.globValueMap = globValueMap;
        this.includesGlobKeys = includesGlobKeys;
        this.nonSkyframeGlobberIncludesToken = nonSkyframeGlobberIncludesToken;
        this.excludes = excludes;
        this.globberOperation = globberOperation;
        this.allowEmpty = allowEmpty;
      }

      private List<String> resolve(NonSkyframeGlobber nonSkyframeGlobber)
          throws BadGlobException, IOException, InterruptedException {
        HashSet<String> matches = new HashSet<>();
        for (SkyKey includeGlobKey : includesGlobKeys) {
          // TODO(bazel-team): NestedSet expansion here is suboptimal.
          boolean foundMatch = false;
          for (PathFragment match : getGlobMatches(includeGlobKey, globValueMap).toList()) {
            matches.add(match.getPathString());
            foundMatch = true;
          }
          if (!allowEmpty && !foundMatch) {
            GlobberUtils.throwBadGlobExceptionEmptyResult(
                ((GlobDescriptor) includeGlobKey.argument()).getPattern(), globberOperation);
          }
        }
        if (nonSkyframeGlobberIncludesToken != null) {
          matches.addAll(nonSkyframeGlobber.fetchUnsorted(nonSkyframeGlobberIncludesToken));
        }
        try {
          UnixGlob.removeExcludes(matches, excludes);
        } catch (UnixGlob.BadPattern ex) {
          throw new BadGlobException(ex.getMessage());
        }
        List<String> result = new ArrayList<>(matches);

        if (!allowEmpty && result.isEmpty()) {
          GlobberUtils.throwBadGlobExceptionAllExcluded(globberOperation);
        }
        return result;
      }

      private static NestedSet<PathFragment> getGlobMatches(
          SkyKey globKey, SkyframeLookupResult globValueMap) throws IOException {
        try {
          return checkNotNull(
                  (GlobValue)
                      globValueMap.getOrThrow(
                          globKey, BuildFileNotFoundException.class, IOException.class),
                  "%s should not be missing",
                  globKey)
              .getMatches();
        } catch (BuildFileNotFoundException e) {
          throw new SkyframeGlobbingIOException(e);
        }
      }
    }
  }

  private static class SkyframeGlobbingIOException extends IOException {
    private SkyframeGlobbingIOException(BuildFileNotFoundException cause) {
      super(cause.getMessage(), cause);
    }
  }

  private GlobberWithSkyframeGlobDeps makeGlobber(
      Path buildFilePath,
      PackageIdentifier packageId,
      ImmutableSet<PathFragment> repositoryIgnoredPatterns,
      Root packageRoot,
      Environment env,
      SkyKey keyForMetrics) {
    NonSkyframeGlobber nonSkyframeGlobber =
        packageFactory.createNonSkyframeGlobber(
            buildFilePath.getParentDirectory(),
            packageId,
            repositoryIgnoredPatterns,
            packageLocator,
            threadStateReceiverFactoryForMetrics.apply(keyForMetrics));
    switch (globbingStrategy) {
      case SKYFRAME_HYBRID:
        return new SkyframeHybridGlobber(packageId, packageRoot, env, nonSkyframeGlobber);
      case NON_SKYFRAME:
        // Skyframe globbing is only useful for incremental correctness and performance. The
        // first time Bazel loads a package ever, Skyframe globbing is actually pure overhead
        // (SkyframeHybridGlobber will make full use of NonSkyframeGlobber).
        return new NonSkyframeGlobberWithNoGlobDeps(nonSkyframeGlobber);
    }
    throw new AssertionError(globbingStrategy);
  }

  /**
   * Constructs a {@link Package} object for the given package. Note that the returned package may
   * be in error.
   *
   * <p>May return null if the computation has to be restarted.
   */
  @Nullable
  private LoadedPackage loadPackage(
      PackageLookupValue packageLookupValue,
      PackageIdentifier packageId,
      StarlarkBuiltinsValue starlarkBuiltinsValue,
      Root packageRoot,
      Environment env,
      SkyKey keyForMetrics,
      State state)
      throws InterruptedException, PackageFunctionException {
    WorkspaceNameValue workspaceNameValue =
        (WorkspaceNameValue) env.getValue(WorkspaceNameValue.key());
    RepositoryMappingValue repositoryMappingValue =
        (RepositoryMappingValue)
            env.getValue(RepositoryMappingValue.key(packageId.getRepository()));
    RepositoryMappingValue mainRepositoryMappingValue =
        (RepositoryMappingValue) env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN));
    RootedPath buildFileRootedPath = packageLookupValue.getRootedPath(packageId);
    FileValue buildFileValue = packageLookupValue.isVirtual()
            ? getVirtualBuildFileValue(env, buildFileRootedPath)
            : getBuildFileValue(env, buildFileRootedPath);
    RuleVisibility defaultVisibility = PrecomputedValue.DEFAULT_VISIBILITY.get(env);
    ConfigSettingVisibilityPolicy configSettingVisibilityPolicy =
        PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.get(env);
    IgnoredPackagePrefixesValue repositoryIgnoredPackagePrefixes =
        (IgnoredPackagePrefixesValue)
            env.getValue(IgnoredPackagePrefixesValue.key(packageId.getRepository()));
    if (env.valuesMissing()) {
      return null;
    }
    String workspaceName = workspaceNameValue.getName();
    RepositoryMapping repositoryMapping = repositoryMappingValue.getRepositoryMapping();
    ImmutableSet<PathFragment> repositoryIgnoredPatterns =
        repositoryIgnoredPackagePrefixes.getPatterns();
    Label preludeLabel = null;
    // Can be null in tests.
    if (packageFactory != null) {
      // Load the prelude from the same repository as the package being loaded.
      Label rawPreludeLabel = packageFactory.getRuleClassProvider().getPreludeLabel();
      if (rawPreludeLabel != null) {
        PackageIdentifier preludePackage =
            PackageIdentifier.create(
                packageId.getRepository(), rawPreludeLabel.getPackageFragment());
        preludeLabel = Label.createUnvalidated(preludePackage, rawPreludeLabel.getName());
      }
    }

    // TODO(adonovan): opt: evaluate splitting this part out as a separate Skyframe
    // function (PackageCompileFunction, by analogy with BzlCompileFunction).
    // There's a tradeoff between the memory costs of unconditionally storing
    // the PackageCompileValue and the time savings of not having to recompute
    // it situationally, so it's not an obvious strict win.

    // vv ---- begin PackageCompileFunction ---- vv

    if (packageProgress != null) {
      packageProgress.startReadPackage(packageId);
    }
    boolean committed = false;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.CREATE_PACKAGE, packageId.toString())) {
      CompiledBuildFile compiled = state.compiledBuildFile;
      if (compiled == null) {
        if (showLoadingProgress.get()) {
          env.getListener().handle(Event.progress("Loading package: " + packageId));
        }
        compiled =
            compileBuildFile(
                packageId,
                buildFileRootedPath,
                buildFileValue,
                starlarkBuiltinsValue,
                preludeLabel,
                env);
        if (compiled == null) {
          return null; // skyframe restart
        }
        state.compiledBuildFile = compiled;
      }

      // ^^ ---- end PackageCompileFunction ---- ^^

      ImmutableMap<String, Module> loadedModules = null;
      if (compiled.ok()) {
        // Parse the labels in the file's load statements.
        ImmutableList<Pair<String, Location>> programLoads =
            BzlLoadFunction.getLoadsFromProgram(compiled.getProg());
        ImmutableList<Label> loadLabels =
            BzlLoadFunction.getLoadLabels(
                env.getListener(), programLoads, packageId, repositoryMapping);
        if (loadLabels == null) {
          throw PackageFunctionException.builder()
              .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
              .setPackageIdentifier(packageId)
              .setTransience(Transience.PERSISTENT)
              .setMessage("malformed load statements")
              .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
              .build();
        }

        // Compute key for each label in loads.
        ImmutableList.Builder<BzlLoadValue.Key> keys =
            ImmutableList.builderWithExpectedSize(loadLabels.size());
        for (Label loadLabel : loadLabels) {
          keys.add(BzlLoadValue.keyForBuild(loadLabel));
        }

        // Load .bzl modules in parallel.
        Label buildFileLabel;
        try {
          buildFileLabel =
              Label.create(
                  packageId,
                  packageLookupValue.getBuildFileName().getFilenameFragment().getPathString());
        } catch (LabelSyntaxException e) {
          throw new IllegalStateException("Failed to construct label representing BUILD file", e);
        }
        try {
          loadedModules =
              loadBzlModules(
                  env,
                  packageId,
                  "file " + buildFileLabel.getCanonicalForm(),
                  programLoads,
                  keys.build(),
                  starlarkBuiltinsValue.starlarkSemantics,
                  bzlLoadFunctionForInlining);
        } catch (NoSuchPackageException e) {
          throw new PackageFunctionException(e, Transience.PERSISTENT);
        }
        if (loadedModules == null) {
          return null; // skyframe restart
        }
      }

      // From this point on, no matter whether the function returns
      // successfully or throws an exception, there will be no more
      // Skyframe restarts.
      committed = true;

      long startTimeNanos = BlazeClock.nanoTime();

      // Create the package,
      // even if it will be empty because we cannot attempt execution.
      Package.Builder pkgBuilder =
          packageFactory
              .newPackageBuilder(
                  packageId,
                  workspaceName,
                  starlarkBuiltinsValue.starlarkSemantics,
                  repositoryMapping,
                  mainRepositoryMappingValue.getRepositoryMapping())
              .setFilename(buildFileRootedPath)
              .setDefaultVisibility(defaultVisibility)
              // "defaultVisibility" comes from the command line.
              // Let's give the BUILD file a chance to set default_visibility once,
              // by resetting the PackageBuilder.defaultVisibilitySet flag.
              .setDefaultVisibilitySet(false)
              .setConfigSettingVisibilityPolicy(configSettingVisibilityPolicy);

      Set<SkyKey> globDepKeys = ImmutableSet.of();

      // OK to execute BUILD program?
      if (compiled.ok()) {
        GlobberWithSkyframeGlobDeps globber =
            makeGlobber(
                buildFileRootedPath.asPath(),
                packageId,
                repositoryIgnoredPatterns,
                packageRoot,
                env,
                keyForMetrics);

        pkgBuilder.setGeneratorMap(compiled.getGeneratorMap());

        packageFactory.executeBuildFile(
            pkgBuilder,
            compiled.getProg(),
            compiled.getGlobs(),
            compiled.getGlobsWithDirs(),
            compiled.getSubpackages(),
            compiled.getPredeclared(),
            loadedModules,
            starlarkBuiltinsValue.starlarkSemantics,
            globber);

        globDepKeys = globber.getGlobDepsRequested();

      } else {
        // Execution not attempted due to static errors.
        for (SyntaxError err : compiled.getErrors()) {
          pkgBuilder.addEvent(
              Package.error(err.location(), err.message(), PackageLoading.Code.SYNTAX_ERROR));
        }
        pkgBuilder.setContainsErrors();
      }

      long loadTimeNanos = Math.max(BlazeClock.nanoTime() - startTimeNanos, 0L);
      return new LoadedPackage(pkgBuilder, globDepKeys, loadTimeNanos);

    } finally {
      if (committed) {
        // We're done executing the BUILD file. Therefore, we can discard the compiled BUILD file...
        state.compiledBuildFile = null;
        if (packageProgress != null) {
          // ... and also note that we're done.
          packageProgress.doneReadPackage(packageId);
        }
      }
    }
  }

  /*
   * TEMPORARY: placeholder until virtual build file resolver is API-ified.
   */
  @Nullable
  private static byte[] getVirtualBuildFileContents(RootedPath buildFileRootedPath)
          throws InterruptedException, IOException {
    ProcessBuilder builder = new ProcessBuilder();
    // Get the blob hash
    builder.command("git", "-C", buildFileRootedPath.getRoot().toString(),
            "ls-files", "--error-unmatch", "-s", buildFileRootedPath.getRootRelativePath().toString());
    Process process = builder.start();
    InputStream out = process.getInputStream();
    if (process.waitFor() != 0) {
      throw new FileNotFoundException("Failed to ls-files");
    }

    String fileInfo = new String(out.readAllBytes());
    String blobHash = fileInfo.split(" ")[1];
    // Get the file content
    builder.command("git", "-C", buildFileRootedPath.getRoot().toString(),
            "cat-file", "blob", blobHash);
    process = builder.start();
    out = process.getInputStream();
    if (process.waitFor() != 0) {
      throw new FileNotFoundException("Failed to cat-file");
    }
    return out.readAllBytes();
  }

  // Reads, parses, resolves, and compiles a BUILD file.
  // A read error is reported as PackageFunctionException.
  // A syntax error is reported by returning a CompiledBuildFile with errors.
  // A null result indicates a SkyFrame restart.
  @Nullable
  private CompiledBuildFile compileBuildFile(
      PackageIdentifier packageId,
      RootedPath buildFilePath,
      FileValue buildFileValue,
      StarlarkBuiltinsValue starlarkBuiltinsValue,
      @Nullable Label preludeLabel,
      Environment env)
      throws PackageFunctionException, InterruptedException {
    StarlarkSemantics semantics = starlarkBuiltinsValue.starlarkSemantics;

    // read BUILD file
    Path inputFile = buildFilePath.asPath();
    byte[] buildFileBytes = null;
    try {
      if (buildFileValue.isVirtual()) {
        buildFileBytes = getVirtualBuildFileContents(buildFilePath);
      } else {
        buildFileBytes =
                buildFileValue.isSpecialFile()
                        ? FileSystemUtils.readContent(inputFile)
                        : FileSystemUtils.readWithKnownFileSize(inputFile, buildFileValue.getSize());
      }
    } catch (IOException e) {
      buildFileBytes =
          actionOnIOExceptionReadingBuildFile.maybeGetBuildFileContentsToUse(
              inputFile.asFragment(), e);
      if (buildFileBytes == null) {
        // Note that we did the work that led to this IOException, so we should
        // conservatively report this error as transient.
        throw PackageFunctionException.builder()
            .setType(PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
            .setTransience(Transience.TRANSIENT)
            .setPackageIdentifier(packageId)
            .setMessage(e.getMessage())
            .setException(e)
            .setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
            .build();
      }
      // If control flow reaches here, we're in territory that is deliberately unsound.
      // See the javadoc for ActionOnIOExceptionReadingBuildFile.
    }
    ParserInput input = ParserInput.fromLatin1(buildFileBytes, inputFile.toString());

    // Options for processing BUILD files.
    FileOptions options =
        FileOptions.builder()
            .requireLoadStatementsFirst(false)
            // For historical reasons, BUILD files are allowed to load a symbol
            // and then reassign it later. (It is unclear why this is neccessary).
            // TODO(adonovan): remove this flag and make loads bind file-locally,
            // as in .bzl files. One can always use a renaming load statement.
            .loadBindsGlobally(true)
            .allowToplevelRebinding(true)
            .build();

    // parse
    StarlarkFile file = StarlarkFile.parse(input, options);
    if (!file.ok()) {
      return new CompiledBuildFile(file.errors());
    }

    // Check syntax. Make a pass over the syntax tree to:
    // - reject forbidden BUILD syntax
    // - extract literal glob patterns for prefetching
    // - record the generator_name of each top-level macro call
    Set<String> globs = new HashSet<>();
    Set<String> globsWithDirs = new HashSet<>();
    Set<String> subpackages = new HashSet<>();
    Map<Location, String> generatorMap = new HashMap<>();
    ImmutableList.Builder<SyntaxError> errors = ImmutableList.builder();
    if (!PackageFactory.checkBuildSyntax(
        file, globs, globsWithDirs, subpackages, generatorMap, errors::add)) {
      return new CompiledBuildFile(errors.build());
    }

    // Load (optional) prelude, which determines environment.
    ImmutableMap<String, Object> preludeBindings = null;
    if (preludeLabel != null) {
      Module prelude;
      try {
        prelude = loadPrelude(env, packageId, preludeLabel, bzlLoadFunctionForInlining);
      } catch (NoSuchPackageException e) {
        throw new PackageFunctionException(e, Transience.PERSISTENT);
      }
      if (prelude == null) {
        return null; // skyframe restart
      }
      preludeBindings = prelude.getGlobals();
    }

    // Construct static environment for resolution/compilation.
    // The Resolver.Module defines the set of accessible names
    // (plus special errors for flag-disabled ones), but it is
    // materialized as an ephemeral eval.Module such as will be
    // used later during execution; the two environments must match.
    // TODO(#11437): Remove conditional once disabling injection is no longer allowed.
    Map<String, Object> predeclared =
        semantics.get(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty()
            ? packageFactory.getBazelStarlarkEnvironment().getUninjectedBuildEnv()
            : starlarkBuiltinsValue.predeclaredForBuild;
    if (preludeBindings != null) {
      predeclared = new HashMap<>(predeclared);
      predeclared.putAll(preludeBindings);
    }
    Module module = Module.withPredeclared(semantics, predeclared);

    // Compile BUILD file.
    Program prog;
    try {
      prog = Program.compileFile(file, module);
    } catch (SyntaxError.Exception ex) {
      return new CompiledBuildFile(ex.errors());
    }

    // success
    return new CompiledBuildFile(
        prog,
        ImmutableList.copyOf(globs),
        ImmutableList.copyOf(globsWithDirs),
        ImmutableList.copyOf(subpackages),
        ImmutableMap.copyOf(generatorMap),
        ImmutableMap.copyOf(predeclared));
  }

  private static class InternalInconsistentFilesystemException extends Exception {
    private boolean isTransient;
    private final PackageIdentifier packageIdentifier;

    /**
     * Used to represent a filesystem inconsistency discovered outside the {@link PackageFunction}.
     */
    public InternalInconsistentFilesystemException(
        PackageIdentifier packageIdentifier, InconsistentFilesystemException e) {
      super(e.getMessage(), e);
      this.packageIdentifier = packageIdentifier;
      // This is not a transient error from the perspective of the PackageFunction.
      this.isTransient = false;
    }

    /** Used to represent a filesystem inconsistency discovered by the {@link PackageFunction}. */
    public InternalInconsistentFilesystemException(
        PackageIdentifier packageIdentifier, String inconsistencyMessage) {
      this(packageIdentifier, new InconsistentFilesystemException(inconsistencyMessage));
      this.isTransient = true;
    }

    public boolean isTransient() {
      return isTransient;
    }

    private PackageFunctionException throwPackageFunctionException()
        throws PackageFunctionException {
      throw PackageFunctionException.builder()
          .setType(PackageFunctionException.Type.NO_SUCH_PACKAGE)
          .setPackageIdentifier(packageIdentifier)
          .setMessage(this.getMessage())
          .setException((Exception) this.getCause())
          .setPackageLoadingCode(
              isTransient()
                  ? Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR
                  : Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR)
          .setTransience(isTransient() ? Transience.TRANSIENT : Transience.PERSISTENT)
          .build();
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by {@link
   * PackageFunction#compute}.
   */
  static class PackageFunctionException extends SkyFunctionException {
    public PackageFunctionException(NoSuchPackageException e, Transience transience) {
      super(e, transience);
    }

    static Builder builder() {
      return new Builder();
    }

    /**
     * An enum to help create the different types of {@link NoSuchPackageException}. PackageFunction
     * contains a myriad of different types of exceptions that extend NoSuchPackageException for
     * different scenarios.
     */
    enum Type {
      BUILD_FILE_CONTAINS_ERRORS {
        @Override
        BuildFileContainsErrorsException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return e instanceof IOException
              ? new BuildFileContainsErrorsException(packId, msg, (IOException) e, detailedExitCode)
              : new BuildFileContainsErrorsException(packId, msg, detailedExitCode);
        }
      },
      BUILD_FILE_NOT_FOUND {
        @Override
        BuildFileNotFoundException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return new BuildFileNotFoundException(packId, msg, detailedExitCode);
        }
      },
      INVALID_PACKAGE_NAME {
        @Override
        InvalidPackageNameException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return new InvalidPackageNameException(packId, msg, detailedExitCode);
        }
      },
      NO_SUCH_PACKAGE {
        @Override
        NoSuchPackageException create(
            PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e) {
          return e != null
              ? new NoSuchPackageException(packId, msg, e, detailedExitCode)
              : new NoSuchPackageException(packId, msg, detailedExitCode);
        }
      };

      abstract NoSuchPackageException create(
          PackageIdentifier packId, String msg, DetailedExitCode detailedExitCode, Exception e);
    }

    /**
     * The builder class for {@link PackageFunctionException} and its {@link NoSuchPackageException}
     * cause.
     */
    static class Builder {
      private Type exceptionType;
      private PackageIdentifier packageIdentifier;
      private Transience transience;
      private Exception exception;
      private String message;
      private PackageLoading.Code packageLoadingCode;

      @CanIgnoreReturnValue
      Builder setType(Type exceptionType) {
        this.exceptionType = exceptionType;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setPackageIdentifier(PackageIdentifier packageIdentifier) {
        this.packageIdentifier = packageIdentifier;
        return this;
      }

      @CanIgnoreReturnValue
      private Builder setTransience(Transience transience) {
        this.transience = transience;
        return this;
      }

      @CanIgnoreReturnValue
      private Builder setException(Exception exception) {
        this.exception = exception;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setMessage(String message) {
        this.message = message;
        return this;
      }

      @CanIgnoreReturnValue
      Builder setPackageLoadingCode(PackageLoading.Code packageLoadingCode) {
        this.packageLoadingCode = packageLoadingCode;
        return this;
      }

      @Override
      public int hashCode() {
        return Objects.hash(
            exceptionType, packageIdentifier, transience, exception, message, packageLoadingCode);
      }

      @Override
      public boolean equals(Object other) {
        if (this == other) {
          return true;
        }
        if (!(other instanceof PackageFunctionException.Builder)) {
          return false;
        }
        PackageFunctionException.Builder otherBuilder = (PackageFunctionException.Builder) other;
        return Objects.equals(exceptionType, otherBuilder.exceptionType)
            && Objects.equals(packageIdentifier, otherBuilder.packageIdentifier)
            && Objects.equals(transience, otherBuilder.transience)
            && Objects.equals(exception, otherBuilder.exception)
            && Objects.equals(message, otherBuilder.message)
            && Objects.equals(packageLoadingCode, otherBuilder.packageLoadingCode);
      }

      NoSuchPackageException buildCause() {
        checkNotNull(exceptionType, "The NoSuchPackageException type must be set.");
        checkNotNull(packageLoadingCode, "The PackageLoading code must be set.");
        DetailedExitCode detailedExitCode = createDetailedExitCode(message, packageLoadingCode);
        return exceptionType.create(packageIdentifier, message, detailedExitCode, exception);
      }

      PackageFunctionException build() {
        return new PackageFunctionException(
            buildCause(), checkNotNull(transience, "Transience must be set"));
      }

      private static DetailedExitCode createDetailedExitCode(
          String message, PackageLoading.Code packageLoadingCode) {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setPackageLoading(PackageLoading.newBuilder().setCode(packageLoadingCode).build())
                .build());
      }
    }
  }
}
