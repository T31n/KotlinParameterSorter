package com.eblan.plugin.kps

data class KpsSettings(
    var modifierPositionMode: ModifierPositionMode = ModifierPositionMode.FIRST_OPTIONAL,
    var sortingMode: ParameterSortingMode = ParameterSortingMode.ALPHABETICAL,
    var aiEndpoint: String = "https://api.openai.com/v1/chat/completions",
    var aiModel: String = "gpt-4o-mini"
)

enum class ModifierPositionMode {
    FIRST_PARAMETER,      // Modifier becomes the very first parameter
    FIRST_OPTIONAL        // Modifier after required params, before other optionals (lint-recommended)
}

enum class ParameterSortingMode {
    ALPHABETICAL,
    AI
}