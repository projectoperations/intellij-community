// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tools.build.bazel.jvmIncBuilder.impl;

import com.intellij.tools.build.bazel.jvmIncBuilder.BuildContext;
import com.intellij.tools.build.bazel.jvmIncBuilder.StorageManager;
import com.intellij.tools.build.bazel.jvmIncBuilder.instrumentation.InstrumentationClassFinder;
import com.intellij.tools.build.bazel.jvmIncBuilder.runner.BytecodeInstrumenter;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;

public class FormsInstrumenter implements BytecodeInstrumenter {
  public FormsInstrumenter(BuildContext context, StorageManager storageManager) {
  }

  @Override
  public String getName() {
    return "Forms Instrumenter";
  }

  @Override
  public byte @Nullable [] instrument(String filePath, ClassReader reader, ClassWriter writer, InstrumentationClassFinder finder) throws Exception {
    return null; // todo
  }
}
