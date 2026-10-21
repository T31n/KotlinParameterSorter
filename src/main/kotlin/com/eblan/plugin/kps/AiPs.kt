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

internal data class AiParameterDescriptor(
    val name: String,
    val type: String,
    val hasDefaultValue: Boolean,
    val isCallback: Boolean
)

internal data class AiParameterGroup(
    val name: String,
    val parameterNames: List<String>
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
        parameters: List<AiParameterDescriptor>
    ): List<AiParameterGroup> {
        val endpoint = parseEndpoint(settings.aiEndpoint)
        val model = settings.aiModel.trim()
        require(model.isNotEmpty()) { "Configure an AI model in Kotlin Parameter Sorter settings." }

        val requestBody = gson.toJson(
            mapOf(
                "model" to model,
                "temperature" to 0.2,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to settings.aiSystemPrompt),
                    mapOf(
                        "role" to "user",
                        "content" to buildPrompt(
                            functionName,
                            parameters
                        )
                    )
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

        return parseGroups(content, parameters.map { it.name })
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

    private fun parseGroups(content: String, expectedNames: List<String>): List<AiParameterGroup> {
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

                AiParameterGroup(sanitizeGroupName(name), parameterNames)
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
