package com.eblan.plugin.kps

data class KpsSettings(var modifierPositionMode: ModifierPositionMode = ModifierPositionMode.FIRST_OPTIONAL)

enum class ModifierPositionMode {
    FIRST_PARAMETER,      // Modifier becomes the very first parameter
    FIRST_OPTIONAL        // Modifier after required params, before other optionals (lint-recommended)
}