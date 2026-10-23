package com.eblan.plugin.kps

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializer
import com.sun.net.httpserver.HttpServer
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KpsArrangementMemoryTest {
    private val settings = KpsSettings(
        aiEndpoint = "http://127.0.0.1",
        aiModel = "test-model",
        aiSystemPrompt = "test-prompt"
    )

    @Test
    fun reusesPersistedGroupsForReorderedParameters() {
        val memory = KpsArrangementMemory()
        val parameters = listOf(
            parameter("title", "String"),
            parameter("subtitle", "String"),
            parameter("onClick", "() -> Unit", isCallback = true)
        )
        val groups = listOf(
            AiParameterGroup("Display", listOf("title", "subtitle")),
            AiParameterGroup("Actions", listOf("onClick"))
        )
        val project = project("/tmp/kps-memory-test")
        memory.remember(project, settings, parameters, groups)

        val serializedState = XmlSerializer.serialize(memory.state)
        val restoredState = XmlSerializer.deserialize(
            serializedState,
            KpsArrangementMemoryState::class.java
        )
        val reloadedMemory = KpsArrangementMemory().apply {
            loadState(restoredState)
        }
        val match = reloadedMemory.findMatch(
            project,
            settings,
            listOf(parameters[2], parameters[0], parameters[1])
        )

        assertNotNull(match)
        assertEquals(groups, match.groups)
        assertTrue(match.newParameters.isEmpty())
    }

    @Test
    fun exactReusePreservesRepeatedGroupNamesAndOrder() {
        val memory = KpsArrangementMemory()
        val parameters = listOf(
            parameter("first", "String"),
            parameter("second", "String"),
            parameter("third", "String")
        )
        val groups = listOf(
            AiParameterGroup("Shared", listOf("first")),
            AiParameterGroup("Other", listOf("second")),
            AiParameterGroup("Shared", listOf("third"))
        )
        val project = project("/tmp/kps-memory-duplicate-groups")
        memory.remember(project, settings, parameters, groups)

        val match = memory.findMatch(project, settings, parameters.reversed())

        assertNotNull(match)
        assertEquals(groups, match.groups)
        assertTrue(match.newParameters.isEmpty())
    }

    @Test
    fun memoryIsScopedToProjectAndAiSettings() {
        val memory = KpsArrangementMemory()
        val parameters = listOf(parameter("id", "String"), parameter("name", "String"))
        memory.remember(
            project("/tmp/kps-memory-scope-one"),
            settings,
            parameters,
            listOf(AiParameterGroup("Identity", listOf("id", "name")))
        )

        assertNull(memory.findMatch(project("/tmp/kps-memory-scope-two"), settings, parameters))
        assertNull(
            memory.findMatch(
                project("/tmp/kps-memory-scope-one"),
                settings.copy(aiSystemPrompt = "different prompt"),
                parameters
            )
        )
    }

    @Test
    fun combinesRelatedMemoriesAndUsesNewestGroupForConflicts() {
        val memory = KpsArrangementMemory()
        val project = project("/tmp/kps-memory-related")
        val shared = parameter("itemId", "String")

        memory.remember(
            project,
            settings,
            listOf(shared, parameter("label", "String")),
            listOf(AiParameterGroup("Details", listOf("itemId", "label")))
        )
        memory.remember(
            project,
            settings,
            listOf(shared, parameter("enabled", "Boolean")),
            listOf(AiParameterGroup("Identity", listOf("itemId")), AiParameterGroup("State", listOf("enabled")))
        )

        val match = memory.findMatch(
            project,
            settings,
            listOf(
                shared,
                parameter("label", "String"),
                parameter("enabled", "Boolean"),
                parameter("newValue", "Int")
            )
        )

        assertNotNull(match)
        assertEquals(
            listOf(
                AiParameterGroup("Identity", listOf("itemId")),
                AiParameterGroup("State", listOf("enabled")),
                AiParameterGroup("Details", listOf("label"))
            ),
            match.groups
        )
        assertEquals(listOf("newValue"), match.newParameters.map { it.name })
    }

    @Test
    fun aNewerPartialArrangementOverridesOlderExactMemoryOnConflicts() {
        val memory = KpsArrangementMemory()
        val project = project("/tmp/kps-memory-newest-conflict")
        val shared = parameter("itemId", "String")
        val label = parameter("label", "String")

        memory.remember(
            project,
            settings,
            listOf(shared, label),
            listOf(AiParameterGroup("Details", listOf("itemId", "label")))
        )
        memory.remember(
            project,
            settings,
            listOf(shared, parameter("enabled", "Boolean")),
            listOf(AiParameterGroup("Identity", listOf("itemId")), AiParameterGroup("State", listOf("enabled")))
        )

        val match = memory.findMatch(project, settings, listOf(shared, label))

        assertNotNull(match)
        assertEquals(
            listOf(
                AiParameterGroup("Identity", listOf("itemId")),
                AiParameterGroup("Details", listOf("label"))
            ),
            match.groups
        )
        assertTrue(match.newParameters.isEmpty())
    }

    @Test
    fun mergesKnownParametersFromOlderArrangementsWithinSavedGroupOrder() {
        val memory = KpsArrangementMemory()
        val project = project("/tmp/kps-memory-ordered-merge")
        val first = parameter("first", "String")
        val shared = parameter("shared", "String")
        val last = parameter("last", "String")

        memory.remember(
            project,
            settings,
            listOf(first, shared),
            listOf(AiParameterGroup("Data", listOf("first", "shared")))
        )
        memory.remember(
            project,
            settings,
            listOf(shared, last),
            listOf(AiParameterGroup("Data", listOf("shared", "last")))
        )

        val match = memory.findMatch(
            project,
            settings,
            listOf(first, shared, last, parameter("newValue", "Int"))
        )

        assertNotNull(match)
        assertEquals(
            listOf(AiParameterGroup("Data", listOf("first", "shared", "last"))),
            match.groups
        )
        assertEquals(listOf("newValue"), match.newParameters.map { it.name })
    }

    @Test
    fun reusesAllRememberedGroupsWithoutRequestingAi() {
        val rememberedGroups = listOf(
            AiParameterGroup("Display", listOf("title", "subtitle")),
            AiParameterGroup("Actions", listOf("onClick"))
        )
        val result = AiParameterSorter.arrange(
            settings.copy(aiEndpoint = "invalid", aiModel = ""),
            apiKey = null,
            functionName = "Child",
            parameters = listOf(
                parameter("title", "String"),
                parameter("subtitle", "String"),
                parameter("onClick", "() -> Unit", isCallback = true)
            ),
            rememberedGroups = rememberedGroups
        )

        assertEquals(rememberedGroups, result)
    }

    @Test
    fun incrementalRequestKeepsSavedGroupsAndPlacesOnlyNewParameters() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requestBody = ""
        val responseContent =
            """{"groups":[{"name":"Actions","parameters":["onRetry"],"afterGroup":null},{"name":"Loading","parameters":["isLoading"],"afterGroup":"Display"}]}"""
        val responseBody = Gson().toJson(
            mapOf(
                "choices" to listOf(
                    mapOf("message" to mapOf("content" to responseContent))
                )
            )
        ).toByteArray(StandardCharsets.UTF_8)
        server.createContext("/chat/completions") { exchange ->
            requestBody = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        }
        server.start()

        try {
            val rememberedGroups = listOf(
                AiParameterGroup("Display", listOf("title", "subtitle")),
                AiParameterGroup("Actions", listOf("onClick"))
            )
            val result = AiParameterSorter.arrange(
                settings.copy(
                    aiEndpoint = "http://127.0.0.1:${server.address.port}/chat/completions"
                ),
                apiKey = null,
                functionName = "Child",
                parameters = listOf(
                    parameter("title", "String"),
                    parameter("subtitle", "String"),
                    parameter("onClick", "() -> Unit", isCallback = true),
                    parameter("isLoading", "Boolean"),
                    parameter("onRetry", "() -> Unit", isCallback = true)
                ),
                rememberedGroups = rememberedGroups
            )

            assertEquals(
                listOf(
                    AiParameterGroup("Display", listOf("title", "subtitle")),
                    AiParameterGroup("Loading", listOf("isLoading")),
                    AiParameterGroup("Actions", listOf("onClick", "onRetry"))
                ),
                result
            )

            val userPrompt = JsonParser.parseString(requestBody)
                .asJsonObject
                .getAsJsonArray("messages")[1]
                .asJsonObject
                .get("content")
                .asString
            val newParametersSection = userPrompt.substringAfter("New parameters:")
            assertTrue(userPrompt.contains("Saved groups:"))
            assertTrue(newParametersSection.contains("\"isLoading\""))
            assertTrue(newParametersSection.contains("\"onRetry\""))
            assertFalse(newParametersSection.contains("\"title\""))
            assertFalse(newParametersSection.contains("\"onClick\""))
        } finally {
            server.stop(0)
        }
    }

    private fun parameter(
        name: String,
        type: String,
        hasDefaultValue: Boolean = false,
        isCallback: Boolean = false
    ): AiParameterDescriptor = AiParameterDescriptor(name, type, hasDefaultValue, isCallback)

    private fun project(basePath: String): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getBasePath" -> basePath
                "getName" -> "kps-memory-test"
                "toString" -> "Project($basePath)"
                else -> null
            }
        } as Project
}
