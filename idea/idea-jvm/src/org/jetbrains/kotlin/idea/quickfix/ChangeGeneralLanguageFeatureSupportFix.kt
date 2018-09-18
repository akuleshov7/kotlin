/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.configuration.BuildSystemType
import org.jetbrains.kotlin.idea.configuration.findApplicableConfigurator
import org.jetbrains.kotlin.idea.configuration.getBuildSystemType
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.idea.roots.invalidateProjectRoots
import org.jetbrains.kotlin.psi.KtFile

sealed class ChangeGeneralLanguageFeatureSupportFix(
    element: PsiElement,
    feature: LanguageFeature,
    featureSupport: LanguageFeature.State
) : AbstractChangeFeatureSupportLevelFix(element, feature, featureSupport, feature.presentableName) {

    class InModule(
        element: PsiElement,
        feature: LanguageFeature,
        featureSupport: LanguageFeature.State
    ) : ChangeGeneralLanguageFeatureSupportFix(element, feature, featureSupport) {
        override fun getText() = "${super.getText()} in the current module"

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return

            findApplicableConfigurator(module).changeGeneralFeatureConfiguration(module, feature, featureSupport)
        }
    }

    class InProject(
        element: PsiElement,
        feature: LanguageFeature,
        featureSupport: LanguageFeature.State
    ) : ChangeGeneralLanguageFeatureSupportFix(element, feature, featureSupport) {
        override fun getText() = "${super.getText()} in the project"

        override fun invoke(project: Project, editor: Editor?, file: KtFile) {
            if (featureSupportEnabled) {
                if (!checkUpdateRuntime(project, feature.sinceApiVersion)) return
            }
            KotlinCompilerSettings.getInstance(project).update {
                // TODO: JPS, project
            }
            project.invalidateProjectRoots()
        }

    }

    companion object : FeatureSupportIntentionActionsFactory() {
        private val supportedFeatures = listOf(LanguageFeature.InlineClasses)

        fun getFixText(feature: LanguageFeature, state: LanguageFeature.State) = getFixText(state, feature.presentableName)

        override fun doCreateActions(diagnostic: Diagnostic): List<IntentionAction> {
            val module = ModuleUtilCore.findModuleForPsiElement(diagnostic.psiElement) ?: return emptyList()

            fun shouldConfigureInProject(): Boolean {
                val facetSettings = KotlinFacet.get(module)?.configuration?.settings
                return (facetSettings == null || facetSettings.useProjectSettings) &&
                        module.getBuildSystemType() == BuildSystemType.JPS
            }

            return supportedFeatures.flatMap { feature ->
                doCreateActions(
                    diagnostic, feature, allowWarningAndErrorMode = false,
                    quickFixConstructor = if (shouldConfigureInProject()) ::InProject else ::InModule
                )
            }
        }
    }
}
