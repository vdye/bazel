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
//

package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.SyntaxError;

import javax.annotation.Nullable;
import java.util.List;

/**
 * CompiledBuildFile holds information extracted from the BUILD syntax tree before it was
 * discarded, such as the compiled program, its glob literals, and its mapping from each function
 * call site to its {@code generator_name} attribute value.
 */
// TODO(adonovan): when we split PackageCompileFunction out, move this there, and make it
// non-public. (Since CompiledBuildFile contains a Module (the prelude), when we split it out,
// the code path that requests it will have to support inlining a la BzlLoadFunction.)
public class CompiledBuildFile {
  // Either errors is null, or all the other fields are.
  @Nullable
  private final ImmutableList<SyntaxError> errors;
  @Nullable private final Program prog;
  @Nullable private final ImmutableList<String> globs;
  @Nullable private final ImmutableList<String> globsWithDirs;
  @Nullable private final ImmutableList<String> subpackages;
  @Nullable private final ImmutableMap<Location, String> generatorMap;
  @Nullable private final ImmutableMap<String, Object> predeclared;

  boolean ok() {
    return prog != null;
  }

  ImmutableList<SyntaxError> getErrors() { return errors; }
  Program getProg() { return prog; }
  ImmutableList<String> getGlobs() { return globs; }
  ImmutableList<String> getGlobsWithDirs() { return globsWithDirs; }
  ImmutableList<String> getSubpackages() { return subpackages; }
  ImmutableMap<Location, String> getGeneratorMap() { return generatorMap; }
  ImmutableMap<String, Object> getPredeclared() { return predeclared; }

  // success
  CompiledBuildFile(
          Program prog,
          ImmutableList<String> globs,
          ImmutableList<String> globsWithDirs,
          ImmutableList<String> subpackages,
          ImmutableMap<Location, String> generatorMap,
          ImmutableMap<String, Object> predeclared) {
    this.errors = null;
    this.prog = prog;
    this.globs = globs;
    this.subpackages = subpackages;
    this.globsWithDirs = globsWithDirs;
    this.generatorMap = generatorMap;
    this.predeclared = predeclared;
  }

  // failure
  CompiledBuildFile(List<SyntaxError> errors) {
    this.errors = ImmutableList.copyOf(errors);
    this.prog = null;
    this.globs = null;
    this.globsWithDirs = null;
    this.subpackages = null;
    this.generatorMap = null;
    this.predeclared = null;
  }
}
