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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.*;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.repository.ExternalPackageHelper;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.skyframe.*;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import net.starlark.java.eval.*;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link SkyFunction} for finding and compiling virtual BUILD files.
 *
 * <p>Most of the complexity in the implementation results from wanting incremental correctness in
 * the presence of symlinks, esp. ancestor directory symlinks.
 */
public class VirtualBuildFileFunction implements SkyFunction {
  private final PackageFactory packageFactory;
  private final DependencyAdapterHelper dependencyAdapterHelper;

  private final ExternalPackageHelper externalPackageHelper;

  public VirtualBuildFileFunction(
          PackageFactory packageFactory,
          DependencyAdapterHelper dependencyAdapterHelper,
          ExternalPackageHelper externalPackageHelper) {
    this.packageFactory = packageFactory;
    this.dependencyAdapterHelper = dependencyAdapterHelper;
    this.externalPackageHelper = externalPackageHelper;
  }

  @Nullable
  @Override
  public CompiledBuildFileValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException {

    RootedPath rootedPath = ((CompiledBuildFileValue.Key) skyKey.argument()).getPathToBuildFile();
    Rule dependencyAdapter = null;
    String rawBuildFile = null;
    // TODO: handle symlinks like FileFunction
    // TODO: use user-specified resolution function

    RootedPath workspacePath = externalPackageHelper.findWorkspaceFile(env);
    if (env.valuesMissing()) {
      return null;
    }

    SkyKey key = WorkspaceFileValue.key(workspacePath);
    if (key == null) {
      return null;
    }
    WorkspaceFileValue workspaceFileValue = (WorkspaceFileValue) env.getValue(key);
    if (workspaceFileValue == null) {
      return null;
    }
    // Walk the entire workspace file to make sure we've found the dependency adapter
    while (workspaceFileValue.next() != null) {
      workspaceFileValue = (WorkspaceFileValue) env.getValue(workspaceFileValue.next());
      if (workspaceFileValue == null) {
        return null;
      }
      if (workspaceFileValue.getPackage().getDependencyAdapter() != null) {
        dependencyAdapter = workspaceFileValue.getPackage().getDependencyAdapter();
      }
    }

    if (dependencyAdapter == null) {
      System.out.println("Dependency adapter is null!");
      return CompiledBuildFileValue.create(null);
    }


//    String defInfo = RepositoryResolvedEvent.getRuleDefinitionInformation(dependencyAdapter);
//    env.getListener().post(new StarlarkRepositoryDefinitionLocationEvent(rule.getName(), defInfo));

    StarlarkCallable function = dependencyAdapter.getRuleClassObject().getConfiguredTargetFunction();
//    if (declareEnvironmentDependencies(markerData, env, getEnviron(rule)) == null) {
//      return null;
//    }
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }
//    markerData.put(SEMANTICS, describeSemantics(starlarkSemantics));
//    markerData.put("ARCH:", CPU.getCurrent().getCanonicalName());

//    Set<String> verificationRules =
//            RepositoryDelegatorFunction.OUTPUT_VERIFICATION_REPOSITORY_RULES.get(env);
//    if (env.valuesMissing()) {
//      return null;
//    }
//    ResolvedHashesValue resolvedHashesValue =
//            (ResolvedHashesValue) env.getValue(ResolvedHashesValue.key());
//    if (env.valuesMissing()) {
//      return null;
//    }
//    Map<String, String> resolvedHashes = checkNotNull(resolvedHashesValue).getHashes();

//    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
//    if (env.valuesMissing()) {
//      return null;
//    }

//    IgnoredPackagePrefixesValue ignoredPackagesValue =
//            (IgnoredPackagePrefixesValue) env.getValue(IgnoredPackagePrefixesValue.key());
//    if (env.valuesMissing()) {
//      return null;
//    }
//    ImmutableSet<PathFragment> ignoredPatterns = checkNotNull(ignoredPackagesValue).getPatterns();

    try (Mutability mu = Mutability.create("Starlark repository")) {
      StarlarkThread thread = new StarlarkThread(mu, starlarkSemantics);
      thread.setPrintHandler(Event.makeDebugPrintHandler(env.getListener()));

      // The fetch phase does not need the tools repository
      // or the fragment map because it happens before analysis.
      new BazelStarlarkContext(
              BazelStarlarkContext.Phase.LOADING, // ("fetch")
              /*toolsRepository=*/ null,
              /*fragmentNameToClass=*/ null,
              new SymbolGenerator<>(key),
              /*analysisRuleLabel=*/ null,
              /*networkAllowlistForTests=*/ null)
              .storeInThread(thread);

      DependencyAdapterContext dependencyAdapterContext =
              dependencyAdapterHelper.createContext(
                      dependencyAdapter,
                      starlarkSemantics,
                      env);

//      if (starlarkRepositoryContext.isRemotable()) {
//        // If a rule is declared remotable then invalidate it if remote execution gets
//        // enabled or disabled.
//        PrecomputedValue.REMOTE_EXECUTION_ENABLED.get(env);
//      }

      // Since restarting a repository function can be really expensive, we first ensure that
      // all label-arguments can be resolved to paths.
//      try {
//        dependencyAdapterContext.enforceLabelAttributes();
//      } catch (NeedsSkyframeRestartException e) {
//        // Missing values are expected; just restart before we actually start the rule
//        return null;
//      } catch (EvalException e) {
//        // EvalExceptions indicate labels not referring to existing files. This is fine,
//        // as long as they are never resolved to files in the execution of the rule; we allow
//        // non-strict rules. So now we have to start evaluating the actual rule, even if that
//        // means the rule might get restarted for legitimate reasons.
//      }

      // This rule is mainly executed for its side effect. Nevertheless, the return value is
      // of importance, as it provides information on how the call has to be modified to be a
      // reproducible rule.
      //
      // Also we do a lot of stuff in there, maybe blocking operations and we should certainly make
      // it possible to return null and not block but it doesn't seem to be easy with Starlark
      // structure as it is.
      Object result;
      try (SilentCloseable c =
                   Profiler.instance()
                           .profile(ProfilerTask.STARLARK_DEPENDENCY_ADAPTER, () -> "dependency adapter")) {
        result =
                Starlark.call(
                        thread,
                        function,
                        /*args=*/ ImmutableList.of(dependencyAdapterContext, rootedPath.getRootRelativePath().toString()),
                        /*kwargs=*/ ImmutableMap.of());
        if (result instanceof String) {
          rawBuildFile = (String)result;
        }
      }
//      RepositoryResolvedEvent resolved =
//              new RepositoryResolvedEvent(
//                      rule, starlarkRepositoryContext.getAttr(), outputDirectory, result);
//      if (resolved.isNewInformationReturned()) {
//        env.getListener().handle(Event.debug(resolved.getMessage()));
//        env.getListener().handle(Event.debug(defInfo));
//      }
//
//      // Modify marker data to include the files used by the rule's implementation function.
//      for (Map.Entry<Label, String> entry :
//              starlarkRepositoryContext.getAccumulatedFileDigests().entrySet()) {
//        // A label does not contain spaces so it's safe to use as a key.
//        markerData.put("FILE:" + entry.getKey(), entry.getValue());
//      }
//
//      String ruleClass =
//              rule.getRuleClassObject().getRuleDefinitionEnvironmentLabel() + "%" + rule.getRuleClass();
//      if (verificationRules.contains(ruleClass)) {
//        String expectedHash = resolvedHashes.get(rule.getName());
//        if (expectedHash != null) {
//          String actualHash = resolved.getDirectoryDigest(syscallCache);
//          if (!expectedHash.equals(actualHash)) {
//            throw new RepositoryFunction.RepositoryFunctionException(
//                    new IOException(
//                            rule + " failed to create a directory with expected hash " + expectedHash),
//                    Transience.PERSISTENT);
//          }
//        }
//      }
//      env.getListener().post(resolved);
    } catch (NeedsSkyframeRestartException e) {
      // A dependency is missing, cleanup and returns null
//      try {
//        if (outputDirectory.exists()) {
//          outputDirectory.deleteTree();
//        }
//      } catch (IOException e1) {
//        throw new RepositoryFunction.RepositoryFunctionException(e1, Transience.TRANSIENT);
//      }
      return null;
    } catch (EvalException e) {
//      env.getListener()
//              .handle(
//                      Event.error(
//                              "An error occurred during the fetch of repository '"
//                                      + rule.getName()
//                                      + "':\n   "
//                                      + e.getMessageWithStack()));
//      env.getListener()
//              .handle(Event.info(RepositoryResolvedEvent.getRuleDefinitionInformation(rule)));
//
//      throw new RepositoryFunction.RepositoryFunctionException(e, Transience.TRANSIENT);

      return CompiledBuildFileValue.create(null);
    }

    if (rawBuildFile != null) {
      StarlarkBuiltinsValue starlarkBuiltinsValue =
              (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
      if (env.valuesMissing()) {
        return null;
      }

      CompiledBuildFile compiledFile;
      try {
        compiledFile =
                PackageFunction.compileBuildFile(
                        rawBuildFile.getBytes(),
                        rootedPath.asPath(),
                        null,
                        packageFactory,
                        starlarkBuiltinsValue,
                        null, // TODO
                        null, // TODO
                        env);
      } catch (Exception e) {
        return CompiledBuildFileValue.create(null);
      }
      if (compiledFile == null) {
        return null;
      }
      return CompiledBuildFileValue.create(compiledFile);
    } else {
      return CompiledBuildFileValue.create(null);
    }
  }
}
