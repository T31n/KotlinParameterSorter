package com.eblan.plugin.kps

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

class Kps : IntentionAction {
    override fun getFamilyName(): String = "Kotlin parameter tools"

    override fun getText(): String =
        if (KpsPersistentStateComponent.getInstance().state.sortingMode == ParameterSortingMode.AI) {
            "Group parameters with AI"
        } else {
            "Sort parameters"
        }

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
        if (settings.sortingMode == ParameterSortingMode.AI) {
            invokeAiMode(project, function, settings)
            return
        }

        val sortedParams = getNewParams(function, originalParams)

        if (originalParams.map { it.name } == sortedParams.map { it.name }) return

        applyParameterOrder(project, function, originalParams, sortedParams)
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
        val isComposable = function.isComposable()
        val apiKey = try {
            AiApiKeyStore.getApiKey()
        } catch (exception: Exception) {
            Messages.showErrorDialog(
                project,
                "Could not read the AI API key from the IDE Password Safe.",
                "AI Parameter Sorter"
            )
            return
        }

        object : Task.Backgroundable(project, "Organizing Kotlin parameters with AI", true) {
            private var groups: List<AiParameterGroup>? = null
            private var failureMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.checkCanceled()
                    groups = AiParameterSorter.arrange(
                        settings,
                        apiKey,
                        functionName,
                        isComposable,
                        settings.modifierPositionMode,
                        descriptors
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
                    currentFunction.isComposable() != isComposable ||
                    currentParams.map { it.toAiDescriptor() } != descriptors
                ) {
                    Messages.showErrorDialog(
                        project,
                        "The function changed or became read-only while AI was organizing it. Run the action again.",
                        "AI Parameter Sorter"
                    )
                    return
                }

                val constrainedGroups = if (isComposable) {
                    applyComposableOrdering(currentParams, aiGroups, settings.modifierPositionMode)
                } else {
                    preserveTrailingLambdaOrdering(currentParams, aiGroups)
                }
                val paramsByName = currentParams.associateBy { it.name }
                val orderedParams = mutableListOf<KtParameter>()
                for (group in constrainedGroups) {
                    for (name in group.parameterNames) {
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
                        constrainedGroups
                    )
                } catch (exception: Exception) {
                    Messages.showErrorDialog(
                        project,
                        exception.message ?: "Could not apply the AI parameter grouping.",
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
        sortedParams: List<KtParameter>,
        aiGroups: List<AiParameterGroup>? = null
    ) {
        val newNameIndexMap = sortedParams.withIndex().associate { it.value.name to it.index }

        WriteCommandAction.runWriteCommandAction(project, "Sort Parameters", null, {
            updateCallSites(function, originalParams, newNameIndexMap)

            val factory = KtPsiFactory(project)

            val cleanParamsString = if (aiGroups == null) {
                sortedParams.joinToString(",\n    ") { it.asParameterText() }
            } else {
                aiGroups.joinToString(",\n    ") { group ->
                    val groupParams = group.parameterNames.map { name ->
                        sortedParams.first { it.name == name }
                    }
                    "// ${group.name}\n    ${groupParams.joinToString(",\n    ") { it.asParameterText() }}"
                }
            }

            val newParamList = factory.createParameterList("(\n    $cleanParamsString\n)")

            function.valueParameterList?.replace(newParamList)

            val documentManager = com.intellij.psi.PsiDocumentManager.getInstance(project)

            documentManager.getDocument(function.containingKtFile)?.let { doc ->
                documentManager.commitDocument(doc)
            }
        }, function.containingFile)
    }

    private fun applyComposableOrdering(
        originalParams: List<KtParameter>,
        aiGroups: List<AiParameterGroup>,
        modifierPositionMode: ModifierPositionMode
    ): List<AiParameterGroup> {
        val modifierParam = originalParams.firstOrNull { it.name == "modifier" }
        val trailingLambdaParam = originalParams.lastOrNull()?.takeIf { it.isCallback() }
        val paramsWithoutModifier = originalParams.filter {
            it != modifierParam && it != trailingLambdaParam
        }
        val requiredNonCallbacks = paramsWithoutModifier.filter {
            !it.isCallback() && it.defaultValue == null
        }
        val optionalNonCallbacks = paramsWithoutModifier.filter {
            !it.isCallback() && it.defaultValue != null
        }
        val callbacks = paramsWithoutModifier.filter { it.isCallback() }
        val aiOrder = aiGroups.flatMap { it.parameterNames }

        val orderedNames = buildList {
            if (modifierPositionMode == ModifierPositionMode.FIRST_PARAMETER) {
                modifierParam?.name?.let(::add)
            }
            addAll(aiOrder.filter { name -> requiredNonCallbacks.any { it.name == name } })
            if (modifierPositionMode == ModifierPositionMode.FIRST_OPTIONAL) {
                modifierParam?.name?.let(::add)
            }
            addAll(aiOrder.filter { name -> optionalNonCallbacks.any { it.name == name } })
            addAll(aiOrder.filter { name -> callbacks.any { it.name == name } })
            trailingLambdaParam?.name?.let(::add)
        }

        return groupsInOrder(aiGroups, orderedNames)
    }

    private fun preserveTrailingLambdaOrdering(
        originalParams: List<KtParameter>,
        aiGroups: List<AiParameterGroup>
    ): List<AiParameterGroup> {
        val trailingLambdaParam = originalParams.lastOrNull()?.takeIf { it.isCallback() } ?: return aiGroups
        val trailingLambdaName = trailingLambdaParam.name ?: return aiGroups
        val orderedNames = aiGroups.flatMap { it.parameterNames }
            .filterNot { it == trailingLambdaName } + trailingLambdaName

        return groupsInOrder(aiGroups, orderedNames)
    }

    private fun groupsInOrder(
        aiGroups: List<AiParameterGroup>,
        orderedNames: List<String>
    ): List<AiParameterGroup> {
        val groupIndexesByName = aiGroups.flatMapIndexed { index, group ->
            group.parameterNames.map { it to index }
        }.toMap()
        val splitGroups = mutableListOf<Pair<Int, MutableList<String>>>()
        for (name in orderedNames) {
            val groupIndex = groupIndexesByName.getValue(name)
            if (splitGroups.lastOrNull()?.first == groupIndex) {
                splitGroups.last().second.add(name)
            } else {
                splitGroups.add(groupIndex to mutableListOf(name))
            }
        }

        return splitGroups.map { (groupIndex, names) ->
            AiParameterGroup(aiGroups[groupIndex].name, names.toList())
        }
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
        newNameIndexMap: Map<String?, Int>
    ) {
        val references = ReferencesSearch.search(function).findAll()

        val factory = KtPsiFactory(function.project)
        val parameterSourceNames = originalParams.mapNotNull { parameter ->
            parameter.name?.let { it to (parameter.nameIdentifier?.text ?: it) }
        }.toMap()

        for (ref in references) {
            val callExpression = ref.element.getParentOfType<KtCallExpression>(true) ?: continue

            val argumentList = callExpression.valueArgumentList ?: continue

            val arguments = argumentList.arguments

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
                    positionalArguments.map { it.first }.sorted() != (0 until positionalArguments.size).toList())
            val reorderedArgs = arrayOfNulls<Triple<Int, KtValueArgument, String?>>(originalParams.size)
            mappedArguments.forEach { (position, argument, parameterName) ->
                reorderedArgs[position] = Triple(position, argument, parameterName)
            }

            val cleanArgsString = reorderedArgs.filterNotNull().joinToString(",\n    ") { (_, argument, parameterName) ->
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
            }

            val newArgList = factory.createCallArguments("(\n    $cleanArgsString\n)")

            argumentList.replace(newArgList)
        }
    }

    private fun getNewParams(function: KtFunction, originalParams: List<KtParameter>): List<KtParameter> {
        val settings = KpsPersistentStateComponent.getInstance().state

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