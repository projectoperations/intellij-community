// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig.style;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.CommonQuickFixBundle;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.dataFlow.NullabilityUtil;
import com.intellij.java.analysis.JavaAnalysisBundle;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.PsiUpdateModCommandQuickFix;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.pom.java.JavaFeature;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.siyeh.ig.callMatcher.CallMatcher;
import com.siyeh.ig.psiutils.CommentTracker;
import com.siyeh.ig.psiutils.TypeUtils;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Set;

import static com.intellij.psi.util.PsiPrecedenceUtil.EQUALITY_PRECEDENCE;
import static com.intellij.psi.util.PsiPrecedenceUtil.METHOD_CALL_PRECEDENCE;

public final class ObjectsEqualsCanBeSimplifiedInspection extends AbstractBaseJavaLocalInspectionTool {
  private static final CallMatcher OBJECTS_EQUALS = CallMatcher.staticCall(
    CommonClassNames.JAVA_UTIL_OBJECTS, "equals").parameterCount(2);

  @Override
  public @NotNull Set<@NotNull JavaFeature> requiredFeatures() {
    return Set.of(JavaFeature.OBJECTS_CLASS);
  }

  @Override
  public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
        if (!OBJECTS_EQUALS.test(call)) return;
        PsiExpression[] args = call.getArgumentList().getExpressions();
        PsiExpression arg1 = args[0];
        PsiExpression arg2 = args[1];
        PsiElement nameElement = Objects.requireNonNull(call.getMethodExpression().getReferenceNameElement());
        if (processPrimitives(nameElement, arg1, arg2)) return;
        PsiClass argClass = PsiUtil.resolveClassInClassTypeOnly(arg1.getType());
        if (argClass == null) return;
        if (NullabilityUtil.getExpressionNullability(arg1, true) == Nullability.NOT_NULL) {
          PsiMethod[] equalsMethods = argClass.findMethodsByName("equals", true);
          for (PsiMethod method : equalsMethods) {
            if (!method.hasModifierProperty(PsiModifier.STATIC) &&
                method.getParameterList().getParametersCount() == 1 &&
                !TypeUtils.isJavaLangObject(Objects.requireNonNull(method.getParameterList().getParameter(0)).getType())) {
              // After replacement may be linked to overloaded equals method
              // even if not, the code becomes more fragile, so let's not suggest the replacement if equals(SomeType) is defined.
              return;
            }
          }
          final @NlsSafe String message = JavaAnalysisBundle.message("inspection.can.be.replaced.with.message", "equals()");
          holder.registerProblem(nameElement, message,
                                 new ReplaceWithEqualsFix(false));
        }
      }

      private boolean processPrimitives(PsiElement nameElement, PsiExpression arg1, PsiExpression arg2) {
        PsiType type1 = arg1.getType();
        PsiType type2 = arg2.getType();
        if (type1 instanceof PsiPrimitiveType && type1.equals(type2) && !TypeConversionUtil.isFloatOrDoubleType(type1)) {
          holder.registerProblem(nameElement, JavaAnalysisBundle.message("inspection.can.be.replaced.with.message", "=="),
                                 new ReplaceWithEqualsFix(true));
          return true;
        }
        return false;
      }
    };
  }

  private static final class ReplaceWithEqualsFix extends PsiUpdateModCommandQuickFix {
    final boolean myEquality;

    private ReplaceWithEqualsFix(boolean equality) {
      myEquality = equality;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Sentence) @NotNull String getFamilyName() {
      return CommonQuickFixBundle.message("fix.replace.x.with.y", "Objects.equals()", myEquality ? "==" : "equals()");
    }

    @Override
    protected void applyFix(@NotNull Project project, @NotNull PsiElement element, @NotNull ModPsiUpdater updater) {
      PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
      if (call == null) return;
      PsiExpression[] args = call.getArgumentList().getExpressions();
      if (args.length != 2) return;
      CommentTracker ct = new CommentTracker();
      String replacement;
      if (myEquality) {
        replacement = ct.text(args[0], EQUALITY_PRECEDENCE) + "==" + ct.text(args[1], EQUALITY_PRECEDENCE);
      }
      else {
        replacement = ct.text(args[0], METHOD_CALL_PRECEDENCE) + ".equals(" + ct.text(args[1]) + ")";
      }
      ct.replaceAndRestoreComments(call, replacement);
    }
  }
}
