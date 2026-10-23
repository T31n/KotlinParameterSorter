package com.eblan.plugin.kps

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale

internal data class AiParameterDescriptor(
    val name: String,
    val type: String,
    val hasDefaultValue: Boolean,
    val isCallback: Boolean
)

internal data class AiParameterGroup(
    val name: String,
    val parameterNames: List<String>,
    val afterGroup: String? = null
)

internal object AiParameterSorter {
    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    fun arrange(
        settings: KpsSettings,
        apiKey: String?,
        functionName: String,
        parameters: List<AiParameterDescriptor>,
        rememberedGroups: List<AiParameterGroup> = emptyList()
    ): List<AiParameterGroup> {
        val rememberedNames = rememberedGroups.flatMap { it.parameterNames }.toSet()
        val newParameters = parameters.filterNot { it.name in rememberedNames }
        if (rememberedGroups.isNotEmpty() && newParameters.isEmpty()) {
            return rememberedGroups
        }

        val incremental = rememberedGroups.isNotEmpty()
        val prompt = if (incremental) {
            buildIncrementalPrompt(functionName, newParameters, rememberedGroups)
        } else {
            buildPrompt(functionName, parameters)
        }
        val expectedNames = if (incremental) newParameters.map { it.name } else parameters.map { it.name }
        val groups = parseGroups(
            requestCompletion(settings, apiKey, prompt),
            expectedNames,
            requireAfterGroup = incremental
        )

        return if (incremental) mergeRememberedGroups(rememberedGroups, groups) else groups
    }

    private fun requestCompletion(
        settings: KpsSettings,
        apiKey: String?,
        prompt: String
    ): String {
        val endpoint = parseEndpoint(settings.aiEndpoint)
        val model = settings.aiModel.trim()
        require(model.isNotEmpty()) { "Configure an AI model in Kotlin Parameter Sorter settings." }

        val requestBody = gson.toJson(
            mapOf(
                "model" to model,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to settings.aiSystemPrompt),
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )

        val requestBuilder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("The AI request was interrupted.", exception)
        } catch (exception: IOException) {
            throw IllegalStateException("Could not connect to the configured AI endpoint.", exception)
        }

        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("The AI endpoint returned HTTP ${response.statusCode()}. Check the endpoint, model, and API key.")
        }

        val content = try {
            val responseJson = JsonParser.parseString(response.body()).asJsonObject
            responseJson.getAsJsonArray("choices")
                ?.firstOrNull()
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
                ?: throw IllegalStateException("The AI endpoint returned no message content.")
        } catch (exception: Exception) {
            if (exception is IllegalStateException && exception.message == "The AI endpoint returned no message content.") {
                throw exception
            }
            throw IllegalStateException("The AI endpoint returned an invalid chat-completions response.", exception)
        }

        return content
    }

    private fun parseEndpoint(endpointText: String): URI {
        val endpoint = try {
            URI(endpointText.trim())
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("Configure a valid HTTP or HTTPS AI endpoint in Kotlin Parameter Sorter settings.")
        }

        require(
            endpoint.scheme in setOf("http", "https") &&
                !endpoint.host.isNullOrBlank() &&
                endpoint.rawUserInfo == null
        ) {
            "Configure a valid HTTP or HTTPS AI endpoint in Kotlin Parameter Sorter settings."
        }
        return endpoint
    }

    private fun buildPrompt(
        functionName: String,
        parameters: List<AiParameterDescriptor>
    ): String {
        val context = gson.toJson(
            mapOf(
                "functionName" to functionName,
                "parameters" to parameters
            )
        )

        return """
            Arrange the Kotlin function parameters into logical groups. Put parameters with closely related purposes next to each other, and give each group a short descriptive name.
            Do not rename, omit, duplicate, or invent parameters. Preserve each parameter name exactly.
            Return only JSON with this exact shape: {"groups":[{"name":"Group name","parameters":["parameterName"]}]}.
            Every input parameter must appear exactly once, and groups must be in final signature order.

            Function signature metadata:
            $context
        """.trimIndent()
    }

    private fun buildIncrementalPrompt(
        functionName: String,
        newParameters: List<AiParameterDescriptor>,
        rememberedGroups: List<AiParameterGroup>
    ): String {
        val rememberedContext = gson.toJson(
            rememberedGroups.map { group ->
                mapOf(
                    "name" to group.name,
                    "parameters" to group.parameterNames
                )
            }
        )
        val newParameterContext = gson.toJson(
            mapOf(
                "functionName" to functionName,
                "parameters" to newParameters
            )
        )

        return """
            Place only the new Kotlin function parameters into groups. The saved groups below are fixed: preserve their names, members, and relative order.
            If a new parameter belongs in an existing group, return that exact group name. Otherwise, create a new group and place it relative to the saved groups using "afterGroup" (use null to place it before the first saved group).
            Return only JSON with this exact shape: {"groups":[{"name":"Group name","parameters":["newParameterName"],"afterGroup":null}]}.
            Set "afterGroup" to JSON null or to the exact name of a saved group.
            Include each new parameter exactly once. Do not include saved parameters in the response. Keep distinct new groups in final order.

            Saved groups:
            $rememberedContext

            New parameters:
            $newParameterContext
        """.trimIndent()
    }

    private fun parseGroups(
        content: String,
        expectedNames: List<String>,
        requireAfterGroup: Boolean = false
    ): List<AiParameterGroup> {
        val jsonContent = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val groups = try {
            val groupsJson = JsonParser.parseString(jsonContent)
                .asJsonObject
                .getAsJsonArray("groups")
                ?: throw IllegalStateException("missing groups")

            groupsJson.map { groupElement ->
                val group = groupElement.asJsonObject
                val name = group.get("name").asRequiredString()
                val parameterNames = group.getAsJsonArray("parameters")
                    ?.map { it.asRequiredString() }
                    ?: throw IllegalStateException("missing group parameters")

                if (parameterNames.isEmpty()) {
                    throw IllegalStateException("empty group")
                }

                if (requireAfterGroup && !group.has("afterGroup")) {
                    throw IllegalStateException("missing afterGroup")
                }
                val afterGroup = if (group.has("afterGroup")) {
                    val afterGroupElement = group.get("afterGroup")
                    if (afterGroupElement.isJsonNull) {
                        null
                    } else {
                        sanitizeGroupName(afterGroupElement.asRequiredString())
                    }
                } else {
                    null
                }

                AiParameterGroup(sanitizeGroupName(name), parameterNames, afterGroup)
            }
        } catch (exception: Exception) {
            throw IllegalStateException("The AI returned an invalid parameter grouping.", exception)
        }

        val orderedNames = groups.flatMap { it.parameterNames }
        if (orderedNames.size != expectedNames.size ||
            orderedNames.toSet() != expectedNames.toSet() ||
            orderedNames.distinct().size != orderedNames.size
        ) {
            throw IllegalStateException("The AI response did not include each function parameter exactly once.")
        }

        return groups
    }

    internal fun mergeRememberedGroups(
        rememberedGroups: List<AiParameterGroup>,
        newGroups: List<AiParameterGroup>
    ): List<AiParameterGroup> {
        val rememberedGroupIndices = linkedMapOf<String, Int>()
        rememberedGroups.forEachIndexed { index, group ->
            rememberedGroupIndices.putIfAbsent(groupKey(group.name), index)
        }

        val parametersToAppend = MutableList(rememberedGroups.size) { mutableListOf<String>() }
        val newGroupsByPosition = MutableList(rememberedGroups.size + 1) {
            linkedMapOf<String, AiParameterGroup>()
        }
        val newGroupPositions = mutableMapOf<String, Int>()

        newGroups.forEach { group ->
            val normalizedName = groupKey(group.name)
            val rememberedIndex = rememberedGroupIndices[normalizedName]
            if (rememberedIndex != null) {
                parametersToAppend[rememberedIndex].addAll(group.parameterNames)
                return@forEach
            }

            val afterIndex = group.afterGroup?.let { afterGroup ->
                rememberedGroupIndices[groupKey(afterGroup)]
                    ?: throw IllegalStateException("The AI referenced an unknown remembered parameter group.")
            }
            val position = afterIndex?.plus(1) ?: 0
            val previousPosition = newGroupPositions.putIfAbsent(normalizedName, position)
            if (previousPosition != null && previousPosition != position) {
                throw IllegalStateException("The AI placed the same new parameter group in conflicting positions.")
            }

            val groupsAtPosition = newGroupsByPosition[position]
            val existingGroup = groupsAtPosition[normalizedName]
            groupsAtPosition[normalizedName] = if (existingGroup == null) {
                group.copy(afterGroup = null)
            } else {
                existingGroup.copy(parameterNames = existingGroup.parameterNames + group.parameterNames)
            }
        }

        return buildList {
            for (position in 0..rememberedGroups.size) {
                addAll(newGroupsByPosition[position].values)
                if (position < rememberedGroups.size) {
                    val rememberedGroup = rememberedGroups[position]
                    add(
                        rememberedGroup.copy(
                            parameterNames = rememberedGroup.parameterNames + parametersToAppend[position],
                            afterGroup = null
                        )
                    )
                }
            }
        }
    }

    private fun groupKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun JsonElement?.asRequiredString(): String {
        if (this == null || !isJsonPrimitive || !asJsonPrimitive.isString) {
            throw IllegalStateException("expected string")
        }
        return asString
    }

    private fun sanitizeGroupName(name: String): String {
        val sanitizedName = name
            .filter { it.isLetterOrDigit() || it in " -/&" }
            .trim()
            .replace(Regex("\\s+"), " ")
            .take(60)

        return sanitizedName.ifEmpty { "Related parameters" }
    }

}
