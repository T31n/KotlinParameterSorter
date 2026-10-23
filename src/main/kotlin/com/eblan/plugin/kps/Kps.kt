package com.eblan.plugin.kps

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

class Kps : IntentionAction {
    override fun getFamilyName(): String = "Kotlin parameter tools"

    override fun getText(): String = "Group parameters with AI"

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

        val settings = KpsPersistentStateComponent.getInstance().state.copy()
        invokeAiMode(project, function, settings)
    }

    private fun invokeAiMode(project: Project, function: KtFunction, settings: KpsSettings) {
        val originalParams = function.valueParameters
        val parameterNames = originalParams.map { it.name }
        if (parameterNames.any { it.isNullOrBlank() } ||
            parameterNames.filterNotNull().distinct().size != originalParams.size
        ) {
            Messages.showErrorDialog(
                project,
                "AI mode requires every function parameter to have a unique name.",
                "AI Parameter Sorter"
            )
            return
        }

        val descriptors = originalParams.map { it.toAiDescriptor() }
        val functionPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(function)
        val functionName = function.name ?: "anonymous"
        val memoryMatch = KpsArrangementMemory.getInstance()
            .findMatch(project, settings, descriptors)
        val apiKey = if (memoryMatch?.newParameters?.isEmpty() == true) {
            null
        } else {
            try {
                AiApiKeyStore.getApiKey()
            } catch (_: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Could not read the AI API key from the IDE Password Safe.",
                    "AI Parameter Sorter"
                )
                return
            }
        }

        object : Task.Backgroundable(project, "Organizing Kotlin parameters", true) {
            private var groups: List<AiParameterGroup>? = null
            private var failureMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.checkCanceled()
                    groups = AiParameterSorter.arrange(
                        settings,
                        apiKey,
                        functionName,
                        descriptors,
                        memoryMatch?.groups.orEmpty()
                    )
                    indicator.checkCanceled()
                } catch (exception: ProcessCanceledException) {
                    throw exception
                } catch (exception: Exception) {
                    failureMessage = exception.message ?: "The AI request failed."
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return

                failureMessage?.let {
                    Messages.showErrorDialog(project, it, "AI Parameter Sorter")
                    return
                }

                val aiGroups = groups
                if (aiGroups == null) {
                    Messages.showErrorDialog(
                        project,
                        "The AI did not return a parameter grouping.",
                        "AI Parameter Sorter"
                    )
                    return
                }

                val currentFunction = functionPointer.element
                if (currentFunction == null || !currentFunction.isValid) {
                    Messages.showErrorDialog(
                        project,
                        "The function is no longer available. Run the action again.",
                        "AI Parameter Sorter"
                    )
                    return
                }

                val currentParams = currentFunction.valueParameters
                if (!currentFunction.isWritable ||
                    (currentFunction.name ?: "anonymous") != functionName ||
                    currentParams.map { it.toAiDescriptor() } != descriptors
                ) {
                    Messages.showErrorDialog(
                        project,
                        "The function changed or became read-only while AI was organizing it. Run the action again.",
                        "AI Parameter Sorter"
                    )
                    return
                }

                val paramsByName = currentParams.associateBy { it.name }
                val orderedParams = mutableListOf<KtParameter>()
                aiGroups.forEach { (_, parameterNames1) ->
                    parameterNames1.forEach { name ->
                        val parameter = paramsByName[name]
                        if (parameter == null) {
                            Messages.showErrorDialog(
                                project,
                                "The AI response did not match the current function parameters.",
                                "AI Parameter Sorter"
                            )
                            return
                        }
                        orderedParams.add(parameter)
                    }
                }

                try {
                    applyParameterOrder(
                        project,
                        currentFunction,
                        currentParams,
                        orderedParams,
                        aiGroups
                    )
                } catch (exception: Exception) {
                    Messages.showErrorDialog(
                        project,
                        exception.message ?: "Could not apply the AI parameter grouping.",
                        "AI Parameter Sorter"
                    )
                    return
                }

                try {
                    KpsArrangementMemory.getInstance().remember(
                        project,
                        settings,
                        descriptors,
                        aiGroups
                    )
                } catch (exception: Exception) {
                    Messages.showErrorDialog(
                        project,
                        exception.message ?: "Could not save the remembered parameter arrangement.",
                        "AI Parameter Sorter"
                    )
                }
            }
        }.queue()
    }

    private fun applyParameterOrder(
        project: Project,
        function: KtFunction,
        originalParams: List<KtParameter>,
        orderedParams: List<KtParameter>,
        aiGroups: List<AiParameterGroup>
    ) {
        val newNameIndexMap = orderedParams.withIndex().associate { it.value.name to it.index }

        WriteCommandAction.runWriteCommandAction(project, "Group Parameters with AI", null, {
            updateCallSites(function, originalParams, newNameIndexMap, aiGroups)

            val factory = KtPsiFactory(project)

            val cleanParamsString = aiGroups.joinToString(",\n    ") { group ->
                val groupParams = group.parameterNames.map { name ->
                    orderedParams.first { it.name == name }
                }
                "// ${group.name}\n    ${groupParams.joinToString(",\n    ") { it.asParameterText() }}"
            }

            val newParamList = factory.createParameterList("(\n    $cleanParamsString\n)")

            function.valueParameterList?.replace(newParamList)

            val documentManager = PsiDocumentManager.getInstance(project)

            documentManager.getDocument(function.containingKtFile)?.let { doc ->
                documentManager.commitDocument(doc)
            }
        }, function.containingFile)
    }

    private fun KtParameter.asParameterText(): String {
        val annotations = annotationEntries.joinToString(" ") { it.text }
        val valVar = valOrVarKeyword?.text?.let { "$it " } ?: ""
        val name = nameIdentifier?.text ?: name ?: ""
        val type = typeReference?.text ?: ""
        val defaultValue = defaultValue?.let { " = ${it.text}" } ?: ""

        return "$annotations ${valVar}${name}: ${type}${defaultValue}".trim()
    }

    private fun KtParameter.toAiDescriptor(): AiParameterDescriptor = AiParameterDescriptor(
        name = name.orEmpty(),
        type = typeReference?.text ?: "unspecified",
        hasDefaultValue = defaultValue != null,
        isCallback = isCallback()
    )

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
        newNameIndexMap: Map<String?, Int>,
        aiGroups: List<AiParameterGroup>
    ) {
        val references = ReferencesSearch.search(function).findAll()

        val factory = KtPsiFactory(function.project)
        val parameterSourceNames = originalParams.mapNotNull { parameter ->
            parameter.name?.let { it to (parameter.nameIdentifier?.text ?: it) }
        }.toMap()
        val groupIndexByParameterName = aiGroups.flatMapIndexed { groupIndex, group ->
            group.parameterNames.map { it to groupIndex }
        }.toMap()

        references.forEach { ref ->
            val callExpression = ref.element.getParentOfType<KtCallExpression>(true) ?: return@forEach

            val argumentList = callExpression.valueArgumentList
            val trailingLambdaArgument = callExpression.lambdaArguments.lastOrNull()
            if (argumentList == null && trailingLambdaArgument == null) return@forEach
            val arguments = argumentList?.arguments.orEmpty()

            val mappedArguments = mutableListOf<Triple<Int, KtValueArgument, String?>>()

            arguments.forEachIndexed { index, arg ->
                val paramName = if (arg.isNamed()) {
                    arg.getArgumentName()?.asName?.asString()
                } else {
                    originalParams.getOrNull(index)?.name
                }

                val newPosition = newNameIndexMap[paramName]

                if (newPosition != null) {
                    mappedArguments.add(Triple(newPosition, arg, paramName))
                }
            }

            val positionalArguments = mappedArguments.filterNot { it.second.isNamed() }
            val needsNamedArguments = positionalArguments.isNotEmpty() &&
                    (arguments.any { it.isNamed() } ||
                            positionalArguments.map { it.first }
                                .sorted() != positionalArguments.indices.toList())
            val reorderedArgs = arrayOfNulls<Triple<Int, KtValueArgument, String?>>(originalParams.size)
            mappedArguments.forEach { (position, argument, parameterName) ->
                reorderedArgs[position] = Triple(position, argument, parameterName)
            }

            val orderedArguments = reorderedArgs.filterNotNull()
            val argumentTexts = mutableListOf<String>()
            var previousGroupIndex: Int? = null
            orderedArguments.forEach { (_, argument, parameterName) ->
                val groupIndex = parameterName?.let(groupIndexByParameterName::get)
                val groupComment = if (groupIndex != null && groupIndex != previousGroupIndex) {
                    "// ${aiGroups[groupIndex].name}\n    "
                } else {
                    ""
                }
                previousGroupIndex = groupIndex

                val argumentText =
                    if (needsNamedArguments && !argument.isNamed()) {
                        val sourceName = parameterName?.let(parameterSourceNames::get)
                        if (sourceName != null) {
                            "$sourceName = ${argument.text.trim()}"
                        } else {
                            argument.text.trim()
                        }
                    } else {
                        argument.text.trim()
                    }
                argumentTexts.add(groupComment + argumentText)
            }

            val lastArgumentGroupIndex = orderedArguments.lastOrNull()
                ?.third
                ?.let(groupIndexByParameterName::get)
            val trailingLambdaGroupIndex = if (trailingLambdaArgument != null) {
                originalParams.lastOrNull()?.name?.let(groupIndexByParameterName::get)
            } else {
                null
            }
            val trailingLambdaComment = if (
                trailingLambdaGroupIndex != null && trailingLambdaGroupIndex != lastArgumentGroupIndex
            ) {
                "// ${aiGroups[trailingLambdaGroupIndex].name}"
            } else {
                null
            }

            if (argumentTexts.isEmpty() && trailingLambdaComment == null) return@forEach

            val cleanArgsString = argumentTexts.joinToString(",\n    ").let { argumentsText ->
                if (trailingLambdaComment == null) {
                    argumentsText
                } else if (argumentsText.isEmpty()) {
                    trailingLambdaComment
                } else {
                    "$argumentsText\n    $trailingLambdaComment"
                }
            }

            val newArgList = factory.createCallArguments("(\n    $cleanArgsString\n)")

            if (argumentList != null) {
                argumentList.replace(newArgList)
            } else {
                val lambdaArgument = trailingLambdaArgument ?: return@forEach
                callExpression.addBefore(newArgList, lambdaArgument)
            }
        }
    }

    private fun KtParameter.isCallback(): Boolean {
        val typeText = typeReference?.text ?: return false

        return typeText.contains("->")
    }
}