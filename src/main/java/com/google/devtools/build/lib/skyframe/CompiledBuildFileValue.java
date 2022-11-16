// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.Interner;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import javax.annotation.Nullable;

/** The result of {@link VirtualBuildFileFunction}. */
@AutoValue
public abstract class CompiledBuildFileValue implements SkyValue {

  /**
   * The compiled build file associated with a compiled virtual BUILD
   * file, as resolved by the dependency adapter.
   */
  @Nullable
  public abstract CompiledBuildFile getCompiledBuildFile();

  @AutoCodec.Instantiator
  public static CompiledBuildFileValue create(@Nullable CompiledBuildFile compiledBuildFile) {
    return new AutoValue_CompiledBuildFileValue(compiledBuildFile);
  }

  public static Key key(RootedPath pathToBuildFile) {
    return Key.create(pathToBuildFile);
  }

  /** {@link SkyKey} for {@link CompiledBuildFileValue} computation. */
  @AutoCodec
  @AutoValue
  public abstract static class Key implements SkyKey {
    private static final Interner<Key> interner = BlazeInterners.newWeakInterner();

    public abstract RootedPath getPathToBuildFile();

    @AutoCodec.Instantiator
    static Key create(RootedPath pathToBuildFile) {
      return interner.intern(new AutoValue_CompiledBuildFileValue_Key(pathToBuildFile));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.VIRTUAL_BUILD_FILE;
    }

    @Memoized
    @Override
    public abstract int hashCode();
  }
}
