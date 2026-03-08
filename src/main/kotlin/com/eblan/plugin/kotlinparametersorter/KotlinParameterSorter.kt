package com.eblan.plugin.kotlinparametersorter

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

class KotlinParameterSorter : IntentionAction {
    override fun getFamilyName(): String = "Kotlin parameter tools"

    override fun getText(): String = "Sort parameters alphabetically"

    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is KtFile) return false

        val element = file.findElementAt(editor.caretModel.offset) ?: return false

        val function = element.getParentOfType<KtFunction>(true) ?: return false

        return function.valueParameters.size > 1
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is KtFile) return

        val element = file.findElementAt(editor.caretModel.offset) ?: return

        val function = element.getParentOfType<KtFunction>(true) ?: return

        val originalParams = function.valueParameters

        if (originalParams.size <= 1) return

        val sortedParams = getNewParams(
            function = function,
            originalParams = originalParams,
        )

        val newNameIndexMap = sortedParams.withIndex().associate { it.value.name to it.index }

        WriteCommandAction.runWriteCommandAction(project, "Sort Parameters Alphabetically", null, {
            updateCallSites(function, originalParams, newNameIndexMap)

            val factory = KtPsiFactory(project)
            val cleanParams = sortedParams.joinToString(", ") { it.text.trim().trimEnd(',') }
            val newParamList = factory.createParameterList("($cleanParams)")

            function.valueParameterList?.replace(newParamList)

            val ktFile = function.containingKtFile
            val documentManager = com.intellij.psi.PsiDocumentManager.getInstance(project)
            val document = documentManager.getDocument(ktFile) ?: return@runWriteCommandAction

            documentManager.commitDocument(document)
            documentManager.doPostponedOperationsAndUnblockDocument(document)
            com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(project).restart(ktFile)
        }, function.containingFile)
    }

    private fun getNewParams(
        function: KtFunction,
        originalParams: List<KtParameter>,
    ): List<KtParameter> {
        val modifierParam = originalParams.firstOrNull { param ->
            param.name == "modifier" && param.typeReference?.text?.contains("Modifier", ignoreCase = true) == true
        }.takeIf {
            function.annotationEntries.any { entry ->
                val text = entry.calleeExpression?.text ?: ""
                text == "Composable" || text.endsWith(".Composable") || text == "@Composable"
            }
        }

        val nonCallbacks = originalParams.filter { it !== modifierParam && !isCallback(it) }.sortedBy { it.name }

        val callbacks = originalParams.filter { isCallback(it) }.sortedBy { it.name }

        return buildList {
            if (modifierParam != null) {
                add(modifierParam)
            }

            addAll(nonCallbacks)

            addAll(callbacks)
        }
    }

    private fun isCallback(param: KtParameter): Boolean {
        val typeText = param.typeReference?.text?.trim() ?: return false

        return "->" in typeText || typeText.endsWith("->") || typeText.contains("() ->") || typeText.contains("suspend () ->")
    }

    private fun updateCallSites(
        function: KtFunction, originalParams: List<KtParameter>, newNameIndexMap: Map<String?, Int>
    ) {
        val references = ReferencesSearch.search(function).findAll()

        for (ref in references) {
            val callExpression = PsiTreeUtil.getParentOfType(ref.element, KtCallExpression::class.java) ?: continue
            val argumentList = callExpression.valueArgumentList ?: continue
            val arguments = argumentList.arguments
            val project = function.project
            val factory = KtPsiFactory(project)

            val reorderedArgs = arrayOfNulls<KtValueArgument>(newNameIndexMap.size)

            arguments.forEachIndexed { index, arg ->
                val paramName = if (arg.isNamed()) {
                    arg.getArgumentName()?.asName?.asString()
                } else {
                    originalParams.getOrNull(index)?.name
                }

                val newPosition = newNameIndexMap[paramName]
                if (newPosition != null && newPosition < reorderedArgs.size) {
                    reorderedArgs[newPosition] = arg
                }
            }

            val cleanArgs = reorderedArgs.filterNotNull().joinToString(", ") { it.text.trim().trimEnd(',') }
            val newArgList = factory.createCallArguments("($cleanArgs)")

            argumentList.replace(newArgList)
        }
    }
}