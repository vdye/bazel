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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.docgen.annot.DocCategory;
import com.google.devtools.build.docgen.annot.DocumentMethods;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtension;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionEvalStarlarkThreadContext;
import com.google.devtools.build.lib.bazel.bzlmod.TagClass;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.*;
import com.google.devtools.build.lib.packages.Package.NameConflictException;
import com.google.devtools.build.lib.packages.PackageFactory.PackageContext;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.RuleFactory.InvalidRuleException;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.repository.DependencyAdapterApi;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.*;

import java.util.Map;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.*;

/**
 * The Starlark module containing the definition of {@code repository_rule} function to define a
 * Starlark remote repository.
 */
@DocumentMethods
public class StarlarkDependencyAdapterModule implements DependencyAdapterApi {

  /* This creates an instance of a dependency adapter with the exported name */
  @Override
  public StarlarkCallable dependencyAdapter(
      StarlarkCallable implementation,
      Object setup,
      Object teardown,
      Object attrs,
      Boolean thread_safe,
      Sequence<?> environ, // <String> expected
      Boolean configure,
      Boolean remotable,
      String doc,
      StarlarkThread thread)
      throws EvalException {
    BazelStarlarkContext context = BazelStarlarkContext.from(thread);
    context.checkLoadingOrWorkspacePhase("dependency_adapter");
    // We'll set the name later, pass the empty string for now.
    RuleClass.Builder builder = new RuleClass.Builder("", RuleClassType.DEPENDENCY_ADAPTER, true);

    ImmutableList<StarlarkThread.CallStackEntry> callstack = thread.getCallStack();
    builder.setCallStack(
        callstack.subList(0, callstack.size() - 1)); // pop 'dependency_adapter' itself

    builder.addAttribute(attr("$thread_safe", BOOLEAN).defaultValue(thread_safe).build());
    builder.addAttribute(attr("$configure", BOOLEAN).defaultValue(configure).build());
    if (thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC)) {
      builder.addAttribute(attr("$remotable", BOOLEAN).defaultValue(remotable).build());
      BaseRuleClasses.execPropertiesAttribute(builder);
    }
    builder.addAttribute(attr("$environ", STRING_LIST).defaultValue(environ).build());
    BaseRuleClasses.commonCoreAndStarlarkAttributes(builder);
    builder.add(attr("expect_failure", STRING));
    if (attrs != Starlark.NONE) {
      for (Map.Entry<String, Descriptor> attr :
          Dict.cast(attrs, String.class, Descriptor.class, "attrs").entrySet()) {
        Descriptor attrDescriptor = attr.getValue();
        AttributeValueSource source = attrDescriptor.getValueSource();
        String attrName = source.convertToNativeName(attr.getKey());
        if (builder.contains(attrName)) {
          throw Starlark.errorf(
              "There is already a built-in attribute '%s' which cannot be overridden", attrName);
        }
        builder.addAttribute(attrDescriptor.build(attrName));
      }
    }
    builder.setConfiguredTargetFunction(implementation);
    BazelModuleContext bzlModule =
        BazelModuleContext.of(Module.ofInnermostEnclosingStarlarkFunction(thread));
    builder.setRuleDefinitionEnvironmentLabelAndDigest(
        bzlModule.label(), bzlModule.bzlTransitiveDigest());
    builder.setWorkspaceOnly();
    return new DependencyAdapterFunction(builder, implementation, setup, teardown);
  }

  // DependencyAdapterFunction is the result of dependency_adapter(...).
  // It is a callable value; calling it yields a Rule instance.
  @StarlarkBuiltin(
      name = "dependency_adapter",
      category = DocCategory.BUILTIN,
      doc =
          "A callable value that may be invoked during evaluation of the WORKSPACE file or within"
              + " the implementation function of a module extension to instantiate and return a"
              + " repository rule.")
  private static final class DependencyAdapterFunction
      implements StarlarkCallable, StarlarkExportable, RuleFunction {
    private final RuleClass.Builder builder;
    private final StarlarkCallable implementation;
    private final Object setup;
    private final Object teardown;
    private Label extensionLabel;
    private String exportedName;

    private DependencyAdapterFunction(RuleClass.Builder builder, StarlarkCallable implementation,
                                      Object setup, Object teardown) {
      this.builder = builder;
      this.implementation = implementation;
      this.setup = setup;
      this.teardown = teardown;
    }

    @Override
    public String getName() {
      return "dependency_adapter";
    }

    @Override
    public boolean isImmutable() {
      return true;
    }

    @Override
    public void export(EventHandler handler, Label extensionLabel, String exportedName) {
      this.extensionLabel = extensionLabel;
      this.exportedName = exportedName;
    }

    @Override
    public boolean isExported() {
      return extensionLabel != null;
    }

    @Override
    public void repr(Printer printer) {
      if (exportedName == null) {
        printer.append("<anonymous starlark dependency adapter>");
      } else {
        printer.append("<starlark dependency adapter " + extensionLabel + "%" + exportedName + ">");
      }
    }

    @Override
    public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      if (!args.isEmpty()) {
        throw new EvalException("unexpected positional arguments");
      }
      // Decide whether we're operating in the new mode (during module extension evaluation) or in
      // legacy mode (during workspace evaluation).
      ModuleExtensionEvalStarlarkThreadContext extensionEvalContext =
          ModuleExtensionEvalStarlarkThreadContext.from(thread);
      if (extensionEvalContext == null) {
        return createRuleLegacy(thread, kwargs);
      }
      if (!isExported()) {
        throw new EvalException("attempting to instantiate a non-exported repository rule");
      }
      extensionEvalContext.createRepo(thread, kwargs, getRuleClass());
      return Starlark.NONE;
    }

    private String getRuleClassName() {
      // If the function ever got exported (the common case), we take the name
      // it was exported to. Only in the not intended case of calling an unexported
      // repository function through an exported macro, we fall back, for lack of
      // alternatives, to the name in the local context.
      // TODO(b/111199163): we probably should disallow the use of non-exported
      // repository rules anyway.
      if (isExported()) {
        return exportedName;
      } else {
        // repository_rules should be subject to the same "exported" requirement
        // as package rules, but sadly we forgot to add the necessary check and
        // now many projects create and instantiate repository_rules without an
        // intervening export; see b/111199163. An incompatible flag is required.

        // The historical workaround was a fragile hack to introspect on the call
        // expression syntax, f() or x.f(), to find the name f, but we no longer
        // have access to the call expression, so now we just create an ugly
        // name from the function. See github.com/bazelbuild/bazel/issues/10441
        return "unexported_" + implementation.getName();
      }
    }

    /* This is where your created dependency adapter is invoked (in the WORKSPACE file) */
    private Object createRuleLegacy(StarlarkThread thread, Dict<String, Object> kwargs)
        throws EvalException, InterruptedException {
      BazelStarlarkContext.from(thread).checkWorkspacePhase("dependency adapter " + exportedName);
      String ruleClassName = getRuleClassName();
      try {
        RuleClass ruleClass = builder.build(ruleClassName, "dependency_adapter"); // TODO: maybe "key" is "dependency_adapter"
        PackageContext context = PackageFactory.getContext(thread);

        WorkspaceFactoryHelper.createAndUpdateDependencyAdapter(
                context.getBuilder(),
                ruleClass,
                WorkspaceFactoryHelper.getFinalKwargs(kwargs),
                thread.getSemantics(),
                thread.getCallStack());
      } catch (InvalidRuleException | NameConflictException | LabelSyntaxException e) {
        throw Starlark.errorf("%s", e.getMessage());
      }

      return Starlark.NONE;
    }

    @Override
    public RuleClass getRuleClass() {
      String name = getRuleClassName();
      return builder.build(name, "dependency_adapter");
    }
  }
}
