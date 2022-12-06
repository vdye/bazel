// Copyright 2019 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.rules.repository;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashFunction;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.actions.ThreadStateReceiver;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ServerDirectories;
import com.google.devtools.build.lib.analysis.util.AnalysisMock;
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleResolutionFunction;
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodRepoRuleValue;
import com.google.devtools.build.lib.bazel.bzlmod.FakeRegistry;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkDependencyAdapterModule;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.starlark.StarlarkRepositoryModule;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.WorkspaceFileValue;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue.SuccessfulRepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.AlreadyReportedRepositoryAccessException;
import com.google.devtools.build.lib.skyframe.BazelSkyframeExecutorConstants;
import com.google.devtools.build.lib.skyframe.BzlCompileFunction;
import com.google.devtools.build.lib.skyframe.BzlLoadFunction;
import com.google.devtools.build.lib.skyframe.BzlmodRepoRuleFunction;
import com.google.devtools.build.lib.skyframe.ClientEnvironmentFunction;
import com.google.devtools.build.lib.skyframe.ContainingPackageLookupFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction.GlobbingStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PrecomputedFunction;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.starlarkbuildapi.repository.RepositoryBootstrap;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequencedRecordingDifferencer;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.starlark.java.eval.StarlarkSemantics;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

/** Tests for {@link RepositoryDelegatorFunction} */
@RunWith(JUnit4.class)
public class RepositoryDelegatorTest extends FoundationTestCase {
  private Path overrideDirectory;
  private MemoizingEvaluator evaluator;
  private RecordingDifferencer differencer;
  private Path rootPath;
  private FakeRegistry.Factory registryFactory;

  @Before
  public void setupDelegator() throws Exception {
    rootPath = scratch.dir("/outputbase");
    scratch.file(
        rootPath.getRelative("MODULE.bazel").getPathString(), "module(name='test',version='0.1')");
    BlazeDirectories directories =
        new BlazeDirectories(
            new ServerDirectories(rootPath, rootPath, rootPath),
            rootPath,
            /* defaultSystemJavabase= */ null,
            TestConstants.PRODUCT_NAME);
    DownloadManager downloader = Mockito.mock(DownloadManager.class);
    RepositoryFunction localRepositoryFunction = new LocalRepositoryFunction();
    ImmutableMap<String, RepositoryFunction> repositoryHandlers =
        ImmutableMap.of(LocalRepositoryRule.NAME, localRepositoryFunction);
    RepositoryDelegatorFunction delegatorFunction =
        new RepositoryDelegatorFunction(
            repositoryHandlers,
            new StarlarkRepositoryFunction(downloader),
            /*isFetch=*/ new AtomicBoolean(true),
            /*clientEnvironmentSupplier=*/ ImmutableMap::of,
            directories,
            BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER);
    AtomicReference<PathPackageLocator> pkgLocator =
        new AtomicReference<>(
            new PathPackageLocator(
                rootPath,
                ImmutableList.of(Root.fromPath(rootPath)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY));
    ExternalFilesHelper externalFilesHelper =
        ExternalFilesHelper.createForTesting(
            pkgLocator,
            ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
            directories);
    differencer = new SequencedRecordingDifferencer();

    ConfiguredRuleClassProvider.Builder builder = new ConfiguredRuleClassProvider.Builder();
    TestRuleClassProvider.addStandardRules(builder);
    builder
        .clearWorkspaceFileSuffixForTesting()
        .addStarlarkBootstrap(new RepositoryBootstrap(new StarlarkRepositoryModule(), new StarlarkDependencyAdapterModule()));
    ConfiguredRuleClassProvider ruleClassProvider = builder.build();

    PackageFactory pkgFactory =
        AnalysisMock.get()
            .getPackageFactoryBuilderForTesting(directories)
            .build(ruleClassProvider, fileSystem);

    registryFactory = new FakeRegistry.Factory();

    HashFunction hashFunction = fileSystem.getDigestFunction().getHashFunction();
    evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(
                    FileStateKey.FILE_STATE,
                    new FileStateFunction(
                        Suppliers.ofInstance(
                            new TimestampGranularityMonitor(BlazeClock.instance())),
                        SyscallCache.NO_CACHE,
                        externalFilesHelper))
                .put(FileValue.FILE, new FileFunction(pkgLocator, directories))
                .put(SkyFunctions.REPOSITORY_DIRECTORY, delegatorFunction)
                .put(
                    SkyFunctions.PACKAGE,
                    new PackageFunction(
                        null,
                        null,
                        null,
                        null,
                        null,
                        /*packageProgress=*/ null,
                        PackageFunction.ActionOnIOExceptionReadingBuildFile.UseOriginalIOException
                            .INSTANCE,
                        GlobbingStrategy.SKYFRAME_HYBRID,
                        k -> ThreadStateReceiver.NULL_INSTANCE))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        new AtomicReference<>(ImmutableSet.of()),
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY,
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    WorkspaceFileValue.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        ruleClassProvider,
                        pkgFactory,
                        directories,
                        /*bzlLoadFunctionForInlining=*/ null))
                .put(
                    SkyFunctions.LOCAL_REPOSITORY_LOOKUP,
                    new LocalRepositoryLookupFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(
                    SkyFunctions.EXTERNAL_PACKAGE,
                    new ExternalPackageFunction(
                        BazelSkyframeExecutorConstants.EXTERNAL_PACKAGE_HELPER))
                .put(SkyFunctions.PRECOMPUTED, new PrecomputedFunction())
                .put(SkyFunctions.BZL_COMPILE, new BzlCompileFunction(pkgFactory, hashFunction))
                .put(
                    SkyFunctions.BZL_LOAD,
                    BzlLoadFunction.create(
                        pkgFactory, directories, hashFunction, Caffeine.newBuilder().build()))
                .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, new ContainingPackageLookupFunction())
                .put(
                    SkyFunctions.IGNORED_PACKAGE_PREFIXES,
                    new IgnoredPackagePrefixesFunction(
                        /*ignoredPackagePrefixesFile=*/ PathFragment.EMPTY_FRAGMENT))
                .put(SkyFunctions.RESOLVED_HASH_VALUES, new ResolvedHashesFunction())
                .put(
                    SkyFunctions.MODULE_FILE,
                    new ModuleFileFunction(registryFactory, rootPath, ImmutableMap.of()))
                .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, new BazelModuleResolutionFunction())
                .put(
                    BzlmodRepoRuleValue.BZLMOD_REPO_RULE,
                    new BzlmodRepoRuleFunction(ruleClassProvider, directories))
                .put(
                    SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                    new ClientEnvironmentFunction(new AtomicReference<>(ImmutableMap.of())))
                .build(),
            differencer);
    overrideDirectory = scratch.dir("/foo");
    scratch.file("/foo/WORKSPACE");
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.DEPENDENCY_FOR_UNCONDITIONAL_FETCHING.set(
        differencer, RepositoryDelegatorFunction.DONT_FETCH_UNCONDITIONALLY);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
    PrecomputedValue.STARLARK_SEMANTICS.set(
        differencer,
        StarlarkSemantics.builder().setBool(BuildLanguageOptions.ENABLE_BZLMOD, true).build());
    RepositoryDelegatorFunction.RESOLVED_FILE_INSTEAD_OF_WORKSPACE.set(
        differencer, Optional.empty());
    PrecomputedValue.REPO_ENV.set(differencer, ImmutableMap.of());
    RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.set(
        differencer, ImmutableSet.of());
    RepositoryDelegatorFunction.RESOLVED_FILE_FOR_VERIFICATION.set(differencer, Optional.empty());
    ModuleFileFunction.IGNORE_DEV_DEPS.set(differencer, false);
    ModuleFileFunction.MODULE_OVERRIDES.set(differencer, ImmutableMap.of());
    BazelModuleResolutionFunction.ALLOWED_YANKED_VERSIONS.set(differencer, ImmutableList.of());
    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES.set(
        differencer, CheckDirectDepsMode.WARNING);
    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE.set(
        differencer, BazelCompatibilityMode.ERROR);
  }

  @Test
  public void testOverride() throws Exception {
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(
        differencer,
        ImmutableMap.of(RepositoryName.createUnvalidated("foo"), overrideDirectory.asFragment()));

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    Path expectedPath = scratch.dir("/outputbase/external/foo");
    Path actualPath = repositoryDirectoryValue.getPath();
    assertThat(actualPath).isEqualTo(expectedPath);
    assertThat(actualPath.isSymbolicLink()).isTrue();
    assertThat(actualPath.readSymbolicLink()).isEqualTo(overrideDirectory.asFragment());
  }

  @Test
  public void testRepositoryDirtinessChecker() throws Exception {
    TimestampGranularityMonitor tsgm = new TimestampGranularityMonitor(new ManualClock());

    RepositoryDirectoryDirtinessChecker checker = new RepositoryDirectoryDirtinessChecker();
    RepositoryName repositoryName = RepositoryName.create("repo");
    RepositoryDirectoryValue.Key key = RepositoryDirectoryValue.key(repositoryName);

    SuccessfulRepositoryDirectoryValue usual =
        RepositoryDirectoryValue.builder()
            .setPath(rootDirectory.getRelative("a"))
            .setDigest(new byte[] {1})
            .build();

    assertThat(checker.check(key, usual, SyscallCache.NO_CACHE, tsgm).isDirty()).isFalse();

    SuccessfulRepositoryDirectoryValue fetchDelayed =
        RepositoryDirectoryValue.builder()
            .setPath(rootDirectory.getRelative("b"))
            .setFetchingDelayed()
            .setDigest(new byte[] {1})
            .build();

    assertThat(checker.check(key, fetchDelayed, SyscallCache.NO_CACHE, tsgm).isDirty()).isTrue();
  }

  @Test
  public void testFetchRepositoryException_eventHandled() throws Exception {
    scratch.file(
        rootPath.getRelative("rule.bzl").getPathString(),
        "def _impl(ctx):",
        "    pass",
        "sample = rule(",
        "    implementation = _impl,",
        "    toolchains = ['//:toolchain_type'],",
        ")");
    scratch.file(
        rootPath.getRelative("BUILD").getPathString(),
        "load('rule.bzl', 'sample')",
        "toolchain_type(name = 'toolchain_type')",
        "sample(name = 'sample')");
    scratch.file(
        rootPath.getRelative("repo_rule.bzl").getPathString(),
        "def _impl(repo_ctx):",
        "# Error: no file written",
        "    pass",
        "broken_repo = repository_rule(implementation = _impl)");
    scratch.overwriteFile(
        rootPath.getRelative("WORKSPACE").getPathString(),
        "load('repo_rule.bzl', 'broken_repo')",
        "broken_repo(name = 'broken')");

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("broken"));
    // Make it be evaluated every time, as we are testing evaluation.
    differencer.invalidate(ImmutableSet.of(key));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .isInstanceOf(AlreadyReportedRepositoryAccessException.class);
    assertThat(eventHandler.hasErrors()).isTrue();
    assertThat(eventHandler.getEvents()).hasSize(1);
  }

  @Test
  public void loadRepositoryNotDefined() throws Exception {
    // WORKSPACE is empty
    scratch.overwriteFile(rootPath.getRelative("WORKSPACE").getPathString(), "");

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated("foo"));
    // Make it be evaluated every time, as we are testing evaluation.
    differencer.invalidate(ImmutableSet.of(key));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg()).contains("Repository '@foo' is not defined");
  }

  @Test
  public void loadRepositoryFromBzlmod() throws Exception {
    scratch.overwriteFile(
        rootPath.getRelative("MODULE.bazel").getPathString(),
        "module(name='aaa',version='0.1')",
        "bazel_dep(name='bazel_tools',version='1.0')");
    FakeRegistry registry =
        registryFactory
            .newFakeRegistry(scratch.dir("modules").getPathString())
            .addModule(
                createModuleKey("bazel_tools", "1.0"),
                "module(name='bazel_tools', version='1.0');");
    ModuleFileFunction.REGISTRIES.set(differencer, ImmutableList.of(registry.getUrl()));
    // Note that bazel_tools is a well-known module, so its repo name will always be "bazel_tools".
    scratch.file(rootPath.getRelative("BUILD").getPathString());
    scratch.file(
        rootPath.getRelative("repo_rule.bzl").getPathString(),
        "def _impl(rctx):",
        " rctx.file('BUILD', '')",
        "fictive_repo_rule = repository_rule(implementation = _impl)");
    // WORKSPACE.bzlmod is preferred when Bzlmod is enabled.
    scratch.file(
        rootPath.getRelative("WORKSPACE.bzlmod").getPathString(),
        "load(':repo_rule.bzl', 'fictive_repo_rule')",
        "fictive_repo_rule(name = 'bazel_tools')",
        "fictive_repo_rule(name = 'C')");
    scratch.file(
        rootPath.getRelative("WORKSPACE").getPathString(),
        "load(':repo_rule.bzl', 'fictive_repo_rule')",
        "fictive_repo_rule(name = 'bazel_tools')");

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.BAZEL_TOOLS);
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    // bazel_tools should be fetched from MODULE.bazel file instead of WORKSPACE file.
    // Because FakeRegistry will look for the contents of bazel_tools under
    // $scratch/modules/bazel_tools which doesn't exist, the fetch should fail as expected.
    assertThat(result.hasError()).isTrue();
    assertThat(result.getError().getException())
        .hasMessageThat()
        .contains("but it does not exist or is not a directory");

    // C should still be fetched from WORKSPACE.bzlmod successfully.
    loadRepo("C");
  }

  @Test
  public void loadInvisibleRepository() throws Exception {

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key =
        RepositoryDirectoryValue.key(
            RepositoryName.createUnvalidated("foo")
                .toNonVisible(RepositoryName.createUnvalidated("fake_owner_repo")));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains("No repository visible as '@foo' from repository '@fake_owner_repo'");
  }

  @Test
  public void loadInvisibleRepositoryFromMain() throws Exception {

    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key =
        RepositoryDirectoryValue.key(
            RepositoryName.createUnvalidated("foo").toNonVisible(RepositoryName.MAIN));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);

    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isFalse();
    assertThat(repositoryDirectoryValue.getErrorMsg())
        .contains("No repository visible as '@foo' from main repository");
  }

  private void loadRepo(String strippedRepoName) throws InterruptedException {
    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createUnvalidated(strippedRepoName));
    // Make it be evaluated every time, as we are testing evaluation.
    differencer.invalidate(ImmutableSet.of(key));
    EvaluationContext evaluationContext =
        EvaluationContext.newBuilder()
            .setKeepGoing(false)
            .setNumThreads(8)
            .setEventHandler(eventHandler)
            .build();
    EvaluationResult<SkyValue> result =
        evaluator.evaluate(ImmutableList.of(key), evaluationContext);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    assertThat(repositoryDirectoryValue.repositoryExists()).isTrue();
  }
}
