// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.generate

import com.intellij.codeInsight.actions.CodeInsightAction
import org.jetbrains.kotlin.idea.codeInsight.generate.AbstractCodeInsightActionTest
import org.jetbrains.kotlin.idea.k2.codeinsight.generate.KotlinGenerateSecondaryConstructorAction

abstract class AbstractFirGenerateSecondaryConstructorActionTest : AbstractCodeInsightActionTest() {
    override fun createAction(fileText: String): CodeInsightAction {
        return KotlinGenerateSecondaryConstructorAction()
    }
}