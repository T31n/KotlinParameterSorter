package com.eblan.plugin.kps

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Locale

data class KpsArrangementMemoryState(
    var arrangements: MutableList<KpsArrangementMemoryEntry> = mutableListOf()
)

data class KpsArrangementMemoryEntry(
    var projectKey: String = "",
    var settingsFingerprint: String = "",
    var signatureFingerprint: String = "",
    var parameters: MutableList<KpsRememberedParameter> = mutableListOf(),
    var groups: MutableList<KpsRememberedGroup> = mutableListOf(),
    var updatedAt: Long = 0
)

data class KpsRememberedParameter(
    var name: String = "",
    var type: String = "",
    var hasDefaultValue: Boolean = false,
    var isCallback: Boolean = false
)

data class KpsRememberedGroup(
    var name: String = "",
    var parameterNames: MutableList<String> = mutableListOf()
)

internal data class KpsArrangementMemoryMatch(
    val groups: List<AiParameterGroup>,
    val newParameters: List<AiParameterDescriptor>
)

@Service(Service.Level.APP)
@State(
    name = "KpsArrangementMemory",
    storages = [Storage("kpsArrangementMemory.xml")]
)
class KpsArrangementMemory : PersistentStateComponent<KpsArrangementMemoryState> {
    private val lock = Any()
    private var myState = KpsArrangementMemoryState()

    override fun getState(): KpsArrangementMemoryState = synchronized(lock) {
        copyState(myState)
    }

    override fun loadState(state: KpsArrangementMemoryState) {
        synchronized(lock) {
            myState = copyState(state).copy(
                arrangements = state.arrangements
                    .filter(::isValidEntry)
                    .map(::copyEntry)
                    .toMutableList()
            )
        }
    }

    internal fun findMatch(
        project: Project,
        settings: KpsSettings,
        parameters: List<AiParameterDescriptor>
    ): KpsArrangementMemoryMatch? = synchronized(lock) {
        val projectKey = projectKey(project)
        val settingsFingerprint = settingsFingerprint(settings)
        val currentByKey = parameters.associateBy(::parameterKey)
        val currentByName = parameters.associateBy { it.name }

        val candidates = myState.arrangements.withIndex()
            .asSequence()
            .filter { (_, entry) ->
                entry.projectKey == projectKey &&
                    entry.settingsFingerprint == settingsFingerprint &&
                    isValidEntry(entry)
            }
            .mapNotNull { (index, entry) ->
                val rememberedKeys = entry.parameters
                    .map { parameterKey(it.toDescriptor()) }
                    .toSet()
                if (currentByKey.keys.none { it in rememberedKeys }) {
                    null
                } else {
                    MemoryCandidate(index, entry)
                }
            }
            .sortedWith(
                compareByDescending<MemoryCandidate> { it.entry.updatedAt }
                    .thenByDescending { it.index }
            )
            .toList()
        if (candidates.isEmpty()) {
            return@synchronized null
        }

        val exactSignature = signatureFingerprint(parameters)
        val exactCandidate = candidates.firstOrNull {
            it.entry.signatureFingerprint == exactSignature
        }
        if (exactCandidate != null && candidates.first() == exactCandidate) {
            return@synchronized KpsArrangementMemoryMatch(
                groups = exactCandidate.entry.groups.map {
                    AiParameterGroup(it.name, it.parameterNames.toList())
                },
                newParameters = emptyList()
            )
        }

        val rememberedGroups = mutableListOf<AiParameterGroup>()
        val assignedGroupByKey = linkedMapOf<String, String>()
        candidates.forEach { candidate ->
            val sourceGroups = matchingGroups(candidate.entry, currentByName)
            val groupsToAdd = sourceGroups.mapNotNull { group ->
                val newNames = group.parameterNames.filter { name ->
                    parameterKey(currentByName.getValue(name)) !in assignedGroupByKey
                }
                if (newNames.isEmpty()) null else group.copy(parameterNames = newNames)
            }
            if (groupsToAdd.isEmpty()) return@forEach

            if (rememberedGroups.isEmpty()) {
                rememberedGroups.addAll(groupsToAdd)
            } else {
                mergeMemoryGroups(
                    rememberedGroups,
                    groupsToAdd,
                    sourceGroups,
                    currentByName,
                    assignedGroupByKey
                )
            }

            groupsToAdd.forEach { group ->
                group.parameterNames.forEach { name ->
                    assignedGroupByKey[parameterKey(currentByName.getValue(name))] = groupKey(group.name)
                }
            }
        }

        if (assignedGroupByKey.isEmpty()) return@synchronized null

        KpsArrangementMemoryMatch(
            groups = rememberedGroups,
            newParameters = parameters.filterNot { parameterKey(it) in assignedGroupByKey }
        )
    }

    internal fun remember(
        project: Project,
        settings: KpsSettings,
        parameters: List<AiParameterDescriptor>,
        groups: List<AiParameterGroup>
    ) {
        require(isValidArrangement(parameters, groups)) {
            "Cannot remember an invalid parameter arrangement."
        }

        synchronized(lock) {
            val projectKey = projectKey(project)
            val settingsFingerprint = settingsFingerprint(settings)
            val signatureFingerprint = signatureFingerprint(parameters)
            val updatedAt = maxOf(
                System.currentTimeMillis(),
                (myState.arrangements.maxOfOrNull { it.updatedAt } ?: 0L) + 1L
            )
            val entry = KpsArrangementMemoryEntry(
                projectKey = projectKey,
                settingsFingerprint = settingsFingerprint,
                signatureFingerprint = signatureFingerprint,
                parameters = parameters.map {
                    KpsRememberedParameter(
                        name = it.name,
                        type = it.type,
                        hasDefaultValue = it.hasDefaultValue,
                        isCallback = it.isCallback
                    )
                }.toMutableList(),
                groups = groups.map {
                    KpsRememberedGroup(
                        name = it.name,
                        parameterNames = it.parameterNames.toMutableList()
                    )
                }.toMutableList(),
                updatedAt = updatedAt
            )

            val updatedEntries = myState.arrangements
                .filterNot {
                    it.projectKey == projectKey &&
                        it.settingsFingerprint == settingsFingerprint &&
                        it.signatureFingerprint == signatureFingerprint
                } + entry
            val retainedForProject = updatedEntries
                .filter { it.projectKey == projectKey }
                .sortedByDescending { it.updatedAt }
                .take(MAX_ENTRIES_PER_PROJECT)
                .toSet()
            val projectPrunedEntries = updatedEntries.filter {
                it.projectKey != projectKey || it in retainedForProject
            }

            myState.arrangements = projectPrunedEntries
                .sortedByDescending { it.updatedAt }
                .take(MAX_ENTRIES_TOTAL)
                .toMutableList()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            myState.arrangements.clear()
        }
    }

    private fun copyState(state: KpsArrangementMemoryState): KpsArrangementMemoryState =
        KpsArrangementMemoryState(state.arrangements.map(::copyEntry).toMutableList())

    private fun copyEntry(entry: KpsArrangementMemoryEntry): KpsArrangementMemoryEntry =
        entry.copy(
            parameters = entry.parameters.map { it.copy() }.toMutableList(),
            groups = entry.groups.map {
                it.copy(parameterNames = it.parameterNames.toMutableList())
            }.toMutableList()
        )

    private fun isValidEntry(entry: KpsArrangementMemoryEntry): Boolean {
        if (entry.projectKey.isBlank() ||
            entry.settingsFingerprint.isBlank() ||
            entry.signatureFingerprint.isBlank() ||
            entry.parameters.isEmpty() ||
            entry.groups.isEmpty()
        ) {
            return false
        }

        return isValidArrangement(
            entry.parameters.map { it.toDescriptor() },
            entry.groups.map { AiParameterGroup(it.name, it.parameterNames) }
        ) && entry.signatureFingerprint == signatureFingerprint(
            entry.parameters.map { it.toDescriptor() }
        )
    }

    private fun isValidArrangement(
        parameters: List<AiParameterDescriptor>,
        groups: List<AiParameterGroup>
    ): Boolean {
        if (parameters.isEmpty() || groups.isEmpty()) return false

        val parameterNames = parameters.map { it.name }
        if (parameterNames.any { it.isBlank() } || parameterNames.distinct().size != parameterNames.size) {
            return false
        }

        val groupedNames = groups.flatMap { it.parameterNames }
        return groups.all { it.name.isNotBlank() && it.parameterNames.isNotEmpty() } &&
            groupedNames.size == parameterNames.size &&
            groupedNames.distinct().size == groupedNames.size &&
            groupedNames.toSet() == parameterNames.toSet()
    }

    private fun matchingGroups(
        entry: KpsArrangementMemoryEntry,
        currentByName: Map<String, AiParameterDescriptor>
    ): List<AiParameterGroup> {
        val rememberedByName = entry.parameters.associateBy { it.name }
        val groupsByName = linkedMapOf<String, AiParameterGroup>()

        entry.groups.forEach { group ->
            val matchingNames = group.parameterNames.filter { name ->
                val rememberedParameter = rememberedByName[name] ?: return@filter false
                val currentParameter = currentByName[name] ?: return@filter false
                parameterKey(rememberedParameter.toDescriptor()) == parameterKey(currentParameter)
            }
            if (matchingNames.isEmpty()) return@forEach

            val key = groupKey(group.name)
            val existingGroup = groupsByName[key]
            groupsByName[key] = if (existingGroup == null) {
                AiParameterGroup(group.name, matchingNames)
            } else {
                existingGroup.copy(parameterNames = existingGroup.parameterNames + matchingNames)
            }
        }

        return groupsByName.values.toList()
    }

    private fun mergeMemoryGroups(
        rememberedGroups: MutableList<AiParameterGroup>,
        groupsToAdd: List<AiParameterGroup>,
        sourceGroups: List<AiParameterGroup>,
        currentByName: Map<String, AiParameterDescriptor>,
        assignedGroupByKey: Map<String, String>
    ) {
        val rememberedIndices = linkedMapOf<String, Int>()
        rememberedGroups.forEachIndexed { index, group ->
            rememberedIndices.putIfAbsent(groupKey(group.name), index)
        }

        val mergedParameterNames = rememberedGroups
            .map { it.parameterNames.toMutableList() }
            .toMutableList()
        val newGroupsByPosition = MutableList(rememberedGroups.size + 1) {
            linkedMapOf<String, AiParameterGroup>()
        }

        groupsToAdd.forEach { group ->
            val key = groupKey(group.name)
            val rememberedIndex = rememberedIndices[key]
            if (rememberedIndex != null) {
                val sourceGroup = sourceGroups.first { groupKey(it.name) == key }
                val sourceNames = sourceGroup.parameterNames.filter { name ->
                    val parameterKey = parameterKey(currentByName.getValue(name))
                    assignedGroupByKey[parameterKey] == null ||
                        assignedGroupByKey[parameterKey] == key
                }
                mergedParameterNames[rememberedIndex] = mergeParameterOrder(
                    mergedParameterNames[rememberedIndex],
                    sourceNames
                )
                return@forEach
            }

            val sourceIndex = sourceGroups.indexOfFirst { groupKey(it.name) == key }
            val precedingIndex = sourceGroups
                .take(sourceIndex)
                .asReversed()
                .firstNotNullOfOrNull { rememberedIndices[groupKey(it.name)] }
            val followingIndex = sourceGroups
                .drop(sourceIndex + 1)
                .firstNotNullOfOrNull { rememberedIndices[groupKey(it.name)] }
            val position = when {
                precedingIndex != null -> precedingIndex + 1
                followingIndex != null -> followingIndex
                else -> rememberedGroups.size
            }
            newGroupsByPosition[position][key] = group
        }

        val mergedGroups = buildList {
            for (position in 0..rememberedGroups.size) {
                addAll(newGroupsByPosition[position].values)
                if (position < rememberedGroups.size) {
                    add(
                        rememberedGroups[position].copy(
                            parameterNames = mergedParameterNames[position]
                        )
                    )
                }
            }
        }
        rememberedGroups.clear()
        rememberedGroups.addAll(mergedGroups)
    }

    private fun mergeParameterOrder(
        rememberedNames: List<String>,
        sourceNames: List<String>
    ): MutableList<String> {
        val mergedNames = rememberedNames.toMutableList()
        sourceNames.forEachIndexed { index, name ->
            if (name in mergedNames) return@forEachIndexed

            val precedingName = sourceNames
                .take(index)
                .asReversed()
                .firstOrNull { it in mergedNames }
            val followingName = sourceNames
                .drop(index + 1)
                .firstOrNull { it in mergedNames }
            val insertionIndex = when {
                precedingName != null -> mergedNames.indexOf(precedingName) + 1
                followingName != null -> mergedNames.indexOf(followingName)
                else -> mergedNames.size
            }
            mergedNames.add(insertionIndex, name)
        }
        return mergedNames
    }

    private fun groupKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun settingsFingerprint(settings: KpsSettings): String = digest(
        listOf(
            settings.aiEndpoint.trim(),
            settings.aiModel.trim(),
            settings.aiSystemPrompt
        ).joinToString("\u0000")
    )

    private fun signatureFingerprint(parameters: List<AiParameterDescriptor>): String =
        digest(
            parameters
                .map {
                    listOf(
                        parameterKey(it),
                        it.hasDefaultValue.toString(),
                        it.isCallback.toString()
                    ).joinToString("\u0000")
                }
                .sorted()
                .joinToString("\u0001")
        )

    private fun projectKey(project: Project): String {
        val identity = project.basePath?.let {
            Paths.get(it).toAbsolutePath().normalize().toString()
        } ?: "project-name:${project.name}"

        return digest(identity)
    }

    private fun parameterKey(parameter: AiParameterDescriptor): String =
        "${parameter.name}\u0000${normalizeType(parameter.type)}"

    private fun normalizeType(type: String): String = type.trim().replace(WHITESPACE, "")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun KpsRememberedParameter.toDescriptor(): AiParameterDescriptor = AiParameterDescriptor(
        name = name,
        type = type,
        hasDefaultValue = hasDefaultValue,
        isCallback = isCallback
    )

    private data class MemoryCandidate(
        val index: Int,
        val entry: KpsArrangementMemoryEntry
    )

    companion object {
        private const val MAX_ENTRIES_PER_PROJECT = 200
        private const val MAX_ENTRIES_TOTAL = 2000
        private val WHITESPACE = Regex("\\s+")

        @JvmStatic
        fun getInstance(): KpsArrangementMemory = service()
    }
}
