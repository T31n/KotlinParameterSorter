package com.eblan.plugin.kotlinparametersorter

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

class KotlinParameterSorter : IntentionAction {
    override fun getFamilyName(): String = "Kotlin parameter tools"
    override fun getText(): String = "Sort parameters alphabetically"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is KtFile) return false
        val element = file.findElementAt(editor.caretModel.offset) ?: return false

        val function = findTargetFunction(element) ?: return false

        return function.isWritable && function.valueParameters.size > 1
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is KtFile) return
        val element = file.findElementAt(editor.caretModel.offset) ?: return

        val function = findTargetFunction(element) ?: return
        val originalParams = function.valueParameters
        if (originalParams.size <= 1) return

        val sortedParams = getNewParams(function, originalParams)

        if (originalParams.map { it.name } == sortedParams.map { it.name }) return

        val newNameIndexMap = sortedParams.withIndex().associate { it.value.name to it.index }

        WriteCommandAction.runWriteCommandAction(project, "Sort Parameters Alphabetically", null, {
            updateCallSites(function, originalParams, newNameIndexMap)

            val factory = KtPsiFactory(project)

            val cleanParamsString = sortedParams.joinToString(",\n    ") { param ->
                val annotations = param.annotationEntries.joinToString(" ") { it.text }
                val name = param.name
                val type = param.typeReference?.text ?: ""
                val defaultValue = param.defaultValue?.let { " = ${it.text}" } ?: ""
                val valVar = param.valOrVarKeyword?.text?.let { "$it " } ?: ""

                "$annotations ${valVar}${name}: ${type}${defaultValue}".trim()
            }

            val newParamList = factory.createParameterList("(\n    $cleanParamsString\n)")
            function.valueParameterList?.replace(newParamList)

            val documentManager = com.intellij.psi.PsiDocumentManager.getInstance(project)

            documentManager.getDocument(function.containingKtFile)?.let { doc ->
                documentManager.commitDocument(doc)
            }
        }, function.containingFile)
    }

    private fun findTargetFunction(element: PsiElement): KtFunction? {
        val callExpr = element.getParentOfType<KtCallExpression>(true)
        if (callExpr != null) {
            val resolved = callExpr.calleeExpression?.mainReference?.resolve()
            if (resolved is KtFunction) return resolved
        }

        return element.getParentOfType<KtFunction>(true)
    }

    private fun updateCallSites(
        function: KtFunction,
        originalParams: List<KtParameter>,
        newNameIndexMap: Map<String?, Int>
    ) {
        val references = ReferencesSearch.search(function).findAll()
        val factory = KtPsiFactory(function.project)

        for (ref in references) {
            val callExpression = ref.element.getParentOfType<KtCallExpression>(true) ?: continue
            val argumentList = callExpression.valueArgumentList ?: continue
            val arguments = argumentList.arguments

            val reorderedArgs = arrayOfNulls<KtValueArgument>(originalParams.size)

            arguments.forEachIndexed { index, arg ->
                val paramName = if (arg.isNamed()) {
                    arg.getArgumentName()?.asName?.asString()
                } else {
                    originalParams.getOrNull(index)?.name
                }

                val newPosition = newNameIndexMap[paramName]
                if (newPosition != null) {
                    reorderedArgs[newPosition] = arg
                }
            }

            val cleanArgsString = reorderedArgs.filterNotNull().joinToString(",\n    ") { it.text.trim() }
            val newArgList = factory.createCallArguments("(\n    $cleanArgsString\n)")
            argumentList.replace(newArgList)
        }
    }

    private fun getNewParams(function: KtFunction, originalParams: List<KtParameter>): List<KtParameter> {
        val settings = KotlinParameterSorterSettingsService.getInstance().state

        if (!function.isComposable()) {
            return originalParams.sortedBy { it.name }
        }

        val modifierParam = originalParams.firstOrNull { it.name == "modifier" }

        val nonCallbacks = originalParams.filter {
            it != modifierParam && !it.isCallback() && it.defaultValue == null
        }.sortedBy { it.name }

        val optionalNonCallbacks = originalParams.filter {
            it != modifierParam && !it.isCallback() && it.defaultValue != null
        }.sortedBy { it.name }

        val callbacks = originalParams.filter {
            it != modifierParam && it.isCallback()
        }.sortedBy { it.name }

        return buildList {
            if (settings.modifierPositionMode == ModifierPositionMode.FIRST_PARAMETER && modifierParam != null) {
                add(modifierParam)
            }

            addAll(nonCallbacks)

            if (settings.modifierPositionMode == ModifierPositionMode.FIRST_OPTIONAL && modifierParam != null) {
                add(modifierParam)
            }

            addAll(optionalNonCallbacks)
            addAll(callbacks)
        }
    }

    private fun KtFunction.isComposable(): Boolean = annotationEntries.any {
        it.shortName?.asString() == "Composable"
    }

    private fun KtParameter.isCallback(): Boolean {
        val typeText = typeReference?.text ?: return false
        return typeText.contains("->")
    }
}