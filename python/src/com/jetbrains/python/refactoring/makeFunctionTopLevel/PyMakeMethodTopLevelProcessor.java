/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.refactoring.makeFunctionTopLevel;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Function;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.imports.AddImportHelper;
import com.jetbrains.python.codeInsight.imports.AddImportHelper.ImportPriority;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.resolve.QualifiedNameFinder;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.jetbrains.python.psi.PyUtil.as;

/**
 * @author Mikhail Golubev
 */
public class PyMakeMethodTopLevelProcessor extends PyBaseMakeFunctionTopLevelProcessor {

  private final List<PyReferenceExpression> myReferencesToSelf = new ArrayList<PyReferenceExpression>();

  public PyMakeMethodTopLevelProcessor(@NotNull PyFunction targetFunction, @NotNull Editor editor) {
    super(targetFunction, editor);
    // It's easier to debug without preview
    setPreviewUsages(!ApplicationManager.getApplication().isInternal());
  }

  @NotNull
  @Override
  protected String getRefactoringName() {
    return PyBundle.message("refactoring.make.method.top.level");
  }

  @Override
  protected void updateExistingFunctionUsages(@NotNull Collection<String> newParamNames, @NotNull UsageInfo[] usages) {
    final PyElementGenerator elementGenerator = PyElementGenerator.getInstance(myProject);

    for (UsageInfo usage : usages) {
      final PsiElement usageElem = usage.getElement();
      if (usageElem == null) {
        continue;
      }

      if (usageElem instanceof PyReferenceExpression) {
        final PyExpression qualifier = ((PyReferenceExpression)usageElem).getQualifier();
        final PyCallExpression callExpr = as(usageElem.getParent(), PyCallExpression.class);
        if (qualifier != null && callExpr != null && callExpr.getArgumentList() != null) {
          if (isPureReferenceExpression(qualifier)) {
            addArguments(callExpr.getArgumentList(), ContainerUtil.map(newParamNames, new Function<String, String>() {
              @Override
              public String fun(String attribute) {
                return qualifier.getText() + "." + attribute;
              }
            }));
          }
          else if (newParamNames.size() == 1) {
            addArguments(callExpr.getArgumentList(), Collections.singleton(qualifier.getText() + "." + ContainerUtil.getFirstItem(newParamNames)));
          }
          else if (!newParamNames.isEmpty()) {
            final PyStatement anchor = PsiTreeUtil.getParentOfType(callExpr, PyStatement.class);
            // TODO meaningful unique target name
            final String targetName = "foo";
            final String assignmentText = targetName + " = " + qualifier.getText();
            final PyAssignmentStatement assignment = elementGenerator.createFromText(LanguageLevel.forElement(callExpr),
                                                                                     PyAssignmentStatement.class,
                                                                                     assignmentText);
            //noinspection ConstantConditions
            anchor.getParent().addBefore(assignment, anchor);
            addArguments(callExpr.getArgumentList(), ContainerUtil.map(newParamNames, new Function<String, String>() {
              @Override
              public String fun(String attribute) {
                return targetName + "." + attribute;
              }
            }));
          }
        }
        
        final PsiFile usageFile = usage.getFile();
        final PsiFile origFile = myFunction.getContainingFile();
        if (usageFile != origFile) {
          final String funcName = myFunction.getName();
          final String origModuleName = QualifiedNameFinder.findShortestImportableName(origFile, origFile.getVirtualFile());
          if (usageFile != null && origModuleName != null && funcName != null) {
            AddImportHelper.addOrUpdateFromImportStatement(usageFile, origModuleName, funcName, null, ImportPriority.PROJECT, null);
          }
        }

        // Will replace/invalidate entire expression
        PyUtil.removeQualifier((PyReferenceExpression)usageElem);
      }
    }
  }

  private static boolean isPureReferenceExpression(@NotNull PyExpression expr) {
    if (!(expr instanceof PyReferenceExpression)) {
      return false;
    }
    final PyExpression qualifier = ((PyReferenceExpression)expr).getQualifier();
    return qualifier == null || isPureReferenceExpression(qualifier);
  }

  @NotNull
  @Override
  protected PyFunction createNewFunction(@NotNull Collection<String> newParams) {
    for (PyReferenceExpression expr : myReferencesToSelf) {
      PyUtil.removeQualifier(expr);
    }
    final PyFunction copied = (PyFunction)myFunction.copy();
    final PyParameter[] params = copied.getParameterList().getParameters();
    if (params.length > 0) {
      params[0].delete();
    }
    addParameters(copied.getParameterList(), newParams);
    return copied;
  }

  @NotNull
  @Override
  protected Set<String> collectNewParameterNames() {
    final Set<String> paramNames = new LinkedHashSet<String>();
    for (ScopeOwner owner : PsiTreeUtil.collectElementsOfType(myFunction, ScopeOwner.class)) {
      final AnalysisResult result = analyseScope(owner);
      if (!result.nonlocalWritesToEnclosingScope.isEmpty()) {
        throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.nonlocal.writes"));
      }
      if (!result.readsOfSelfParametersFromEnclosingScope.isEmpty()) {
        throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.self.reads"));
      }
      if (!result.readsFromEnclosingScope.isEmpty()) {
        throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.outer.scope.reads"));
      }
      if (!result.writesToSelfParameter.isEmpty()) {
        throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.special.usage.of.self"));
      }
      for (PsiElement usage : result.readsOfSelfParameter) {
        if (usage.getParent() instanceof PyTargetExpression) {
          throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.attribute.writes"));
        }
        final PyReferenceExpression parentReference = as(usage.getParent(), PyReferenceExpression.class);
        if (parentReference != null) {
          final String attrName = parentReference.getName();
          if (attrName != null && PyUtil.isClassPrivateName(attrName)) {
            throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.private.attributes"));
          }
          if (parentReference.getParent() instanceof PyCallExpression) {
            throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.method.calls"));
          }
          paramNames.add(attrName);
          myReferencesToSelf.add(parentReference);
        }
        else {
          throw new IncorrectOperationException(PyBundle.message("refactoring.make.function.top.level.error.special.usage.of.self"));
        }
      }
    }
    return paramNames;
  }
}
