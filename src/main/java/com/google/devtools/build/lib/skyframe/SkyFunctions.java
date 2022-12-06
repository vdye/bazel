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

import com.google.common.base.Predicate;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/** Value types in Skyframe. */
public final class SkyFunctions {
  public static final SkyFunctionName PRECOMPUTED =
      SkyFunctionName.createNonHermetic("PRECOMPUTED");
  public static final SkyFunctionName CLIENT_ENVIRONMENT_VARIABLE =
      SkyFunctionName.createNonHermetic("CLIENT_ENVIRONMENT_VARIABLE");
  static final SkyFunctionName ACTION_SKETCH = SkyFunctionName.createHermetic("ACTION_SKETCH");
  public static final SkyFunctionName ACTION_ENVIRONMENT_VARIABLE =
      SkyFunctionName.createHermetic("ACTION_ENVIRONMENT_VARIABLE");
  public static final SkyFunctionName DIRECTORY_LISTING_STATE =
      SkyFunctionName.createNonHermetic("DIRECTORY_LISTING_STATE");
  public static final SkyFunctionName DIRECTORY_LISTING =
      SkyFunctionName.createHermetic("DIRECTORY_LISTING");
  // Hermetic even though package lookups secretly access the set of deleted packages, because
  // SequencedSkyframeExecutor deletes any affected PACKAGE_LOOKUP nodes when that set changes.
  public static final SkyFunctionName PACKAGE_LOOKUP =
      SkyFunctionName.createHermetic("PACKAGE_LOOKUP");
  public static final SkyFunctionName CONTAINING_PACKAGE_LOOKUP =
      SkyFunctionName.createHermetic("CONTAINING_PACKAGE_LOOKUP");
  public static final SkyFunctionName BZL_COMPILE = SkyFunctionName.createHermetic("BZL_COMPILE");
  public static final SkyFunctionName STARLARK_BUILTINS =
      SkyFunctionName.createHermetic("STARLARK_BUILTINS");
  public static final SkyFunctionName BZL_LOAD = SkyFunctionName.createHermetic("BZL_LOAD");
  public static final SkyFunctionName GLOB = SkyFunctionName.createHermetic("GLOB");
  public static final SkyFunctionName PACKAGE = SkyFunctionName.createHermetic("PACKAGE");
  static final SkyFunctionName PACKAGE_ERROR = SkyFunctionName.createHermetic("PACKAGE_ERROR");
  public static final SkyFunctionName PACKAGE_ERROR_MESSAGE =
      SkyFunctionName.createHermetic("PACKAGE_ERROR_MESSAGE");
  // Semi-hermetic because accesses package locator
  public static final SkyFunctionName TARGET_PATTERN =
      SkyFunctionName.createSemiHermetic("TARGET_PATTERN");
  static final SkyFunctionName TARGET_PATTERN_ERROR =
      SkyFunctionName.createHermetic("TARGET_PATTERN_ERROR");
  public static final SkyFunctionName PREPARE_DEPS_OF_PATTERNS =
      SkyFunctionName.createHermetic("PREPARE_DEPS_OF_PATTERNS");
  // Non-hermetic because accesses package locator
  public static final SkyFunctionName PREPARE_DEPS_OF_PATTERN =
      SkyFunctionName.createNonHermetic("PREPARE_DEPS_OF_PATTERN");
  public static final SkyFunctionName PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY =
      SkyFunctionName.createHermetic("PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY");
  public static final SkyFunctionName PREPARE_TEST_SUITES_UNDER_DIRECTORY =
      SkyFunctionName.createHermetic("PREPARE_TEST_SUITES_UNDER_DIRECTORY");
  public static final SkyFunctionName COLLECT_TARGETS_IN_PACKAGE =
      SkyFunctionName.createHermetic("COLLECT_TARGETS_IN_PACKAGE");
  public static final SkyFunctionName COLLECT_TEST_SUITES_IN_PACKAGE =
      SkyFunctionName.createHermetic("COLLECT_TEST_SUITES_IN_PACKAGE");
  public static final SkyFunctionName COLLECT_PACKAGES_UNDER_DIRECTORY =
      SkyFunctionName.createHermetic("COLLECT_PACKAGES_UNDER_DIRECTORY");
  public static final SkyFunctionName IGNORED_PACKAGE_PREFIXES =
      SkyFunctionName.createHermetic("IGNORED_PACKAGE_PREFIXES");
  static final SkyFunctionName TEST_SUITE_EXPANSION =
      SkyFunctionName.createHermetic("TEST_SUITE_EXPANSION");
  static final SkyFunctionName TESTS_IN_SUITE = SkyFunctionName.createHermetic("TESTS_IN_SUITE");
  // Non-hermetic because accesses package locator
  static final SkyFunctionName TARGET_PATTERN_PHASE =
      SkyFunctionName.createNonHermetic("TARGET_PATTERN_PHASE");
  static final SkyFunctionName PREPARE_ANALYSIS_PHASE =
      SkyFunctionName.createNonHermetic("PREPARE_ANALYSIS_PHASE");
  static final SkyFunctionName RECURSIVE_PKG = SkyFunctionName.createHermetic("RECURSIVE_PKG");
  public static final SkyFunctionName CONFIGURED_TARGET =
      SkyFunctionName.createHermetic("CONFIGURED_TARGET");
  static final SkyFunctionName ACTION_LOOKUP_CONFLICT_FINDING =
      SkyFunctionName.createHermetic("ACTION_LOOKUP_CONFLICT_DETECTION");
  static final SkyFunctionName TOP_LEVEL_ACTION_LOOKUP_CONFLICT_FINDING =
      SkyFunctionName.createHermetic("TOP_LEVEL_ACTION_LOOKUP_CONFLICT_DETECTION");
  public static final SkyFunctionName ASPECT = SkyFunctionName.createHermetic("ASPECT");
  static final SkyFunctionName TOP_LEVEL_ASPECTS =
      SkyFunctionName.createHermetic("TOP_LEVEL_ASPECTS");
  static final SkyFunctionName BUILD_TOP_LEVEL_ASPECTS_DETAILS =
      SkyFunctionName.createHermetic("BUILD_TOP_LEVEL_ASPECTS_DETAILS");
  public static final SkyFunctionName TARGET_COMPLETION =
      SkyFunctionName.createHermetic("TARGET_COMPLETION");
  public static final SkyFunctionName ASPECT_COMPLETION =
      SkyFunctionName.createHermetic("ASPECT_COMPLETION");
  static final SkyFunctionName TEST_COMPLETION = SkyFunctionName.createHermetic("TEST_COMPLETION");
  public static final SkyFunctionName BUILD_CONFIGURATION =
      SkyFunctionName.createHermetic("BUILD_CONFIGURATION");
  public static final SkyFunctionName STARLARK_BUILD_SETTINGS_DETAILS =
      SkyFunctionName.createHermetic("STARLARK_BUILD_SETTINGS_DETAILS");
  // Action execution can be nondeterministic, so semi-hermetic.
  public static final SkyFunctionName ACTION_EXECUTION =
      SkyFunctionName.createSemiHermetic("ACTION_EXECUTION");
  public static final SkyFunctionName ARTIFACT_NESTED_SET =
      SkyFunctionName.createHermetic("ARTIFACT_NESTED_SET");
  public static final SkyFunctionName RECURSIVE_FILESYSTEM_TRAVERSAL =
      SkyFunctionName.createHermetic("RECURSIVE_FILESYSTEM_TRAVERSAL");
  public static final SkyFunctionName FILESET_ENTRY =
      SkyFunctionName.createHermetic("FILESET_ENTRY");
  static final SkyFunctionName BUILD_INFO_COLLECTION =
      SkyFunctionName.createHermetic("BUILD_INFO_COLLECTION");
  public static final SkyFunctionName BUILD_INFO = SkyFunctionName.createHermetic("BUILD_INFO");
  public static final SkyFunctionName WORKSPACE_NAME =
      SkyFunctionName.createHermetic("WORKSPACE_NAME");
  public static final SkyFunctionName PLATFORM_MAPPING =
      SkyFunctionName.createHermetic("PLATFORM_MAPPING");
  static final SkyFunctionName COVERAGE_REPORT = SkyFunctionName.createHermetic("COVERAGE_REPORT");
  public static final SkyFunctionName REPOSITORY_DIRECTORY =
      SkyFunctionName.createNonHermetic("REPOSITORY_DIRECTORY");
  public static final SkyFunctionName WORKSPACE_AST =
      SkyFunctionName.createHermetic("WORKSPACE_AST");
  public static final SkyFunctionName EXTERNAL_PACKAGE =
      SkyFunctionName.createHermetic("EXTERNAL_PACKAGE");
  public static final SkyFunctionName ACTION_TEMPLATE_EXPANSION =
      SkyFunctionName.createHermetic("ACTION_TEMPLATE_EXPANSION");
  public static final SkyFunctionName LOCAL_REPOSITORY_LOOKUP =
      SkyFunctionName.createHermetic("LOCAL_REPOSITORY_LOOKUP");
  static final SkyFunctionName REGISTERED_EXECUTION_PLATFORMS =
      SkyFunctionName.createHermetic("REGISTERED_EXECUTION_PLATFORMS");
  static final SkyFunctionName REGISTERED_TOOLCHAINS =
      SkyFunctionName.createHermetic("REGISTERED_TOOLCHAINS");
  static final SkyFunctionName SINGLE_TOOLCHAIN_RESOLUTION =
      SkyFunctionName.createHermetic("SINGLE_TOOLCHAIN_RESOLUTION");
  public static final SkyFunctionName TOOLCHAIN_RESOLUTION =
      SkyFunctionName.createHermetic("TOOLCHAIN_RESOLUTION");
  public static final SkyFunctionName REPOSITORY_MAPPING =
      SkyFunctionName.createHermetic("REPOSITORY_MAPPING");
  public static final SkyFunctionName RESOLVED_FILE =
      SkyFunctionName.createHermetic("RESOLVED_FILE");
  public static final SkyFunctionName RESOLVED_HASH_VALUES =
      SkyFunctionName.createHermetic("RESOLVED_HASH_VALUES");
  public static final SkyFunctionName MODULE_FILE =
      SkyFunctionName.createNonHermetic("MODULE_FILE");
  public static final SkyFunctionName BUILD_DRIVER =
      SkyFunctionName.createNonHermetic("BUILD_DRIVER");
  public static final SkyFunctionName BAZEL_MODULE_RESOLUTION =
      SkyFunctionName.createHermetic("BAZEL_MODULE_RESOLUTION");
  public static final SkyFunctionName BAZEL_MODULE_INSPECTION =
      SkyFunctionName.createHermetic("BAZEL_MODULE_INSPECTION");
  public static final SkyFunctionName MODULE_EXTENSION_RESOLUTION =
      SkyFunctionName.createHermetic("MODULE_EXTENSION_RESOLUTION");
  public static final SkyFunctionName SINGLE_EXTENSION_USAGES =
      SkyFunctionName.createHermetic("SINGLE_EXTENSION_USAGES");
  public static final SkyFunctionName SINGLE_EXTENSION_EVAL =
      SkyFunctionName.createNonHermetic("SINGLE_EXTENSION_EVAL");

  public static final SkyFunctionName VIRTUAL_BUILD_FILE =
          SkyFunctionName.createNonHermetic("VIRTUAL_BUILD_FILE");

  public static Predicate<SkyKey> isSkyFunction(SkyFunctionName functionName) {
    return key -> key.functionName().equals(functionName);
  }
}
