package com.eblan.plugin.kotlinparametersorter

import com.intellij.util.xmlb.annotations.OptionTag

data class KotlinParameterSorterSettings(
    @OptionTag("modifierPositionMode")
    var modifierPositionMode: ModifierPositionMode = ModifierPositionMode.FIRST_OPTIONAL
) : Cloneable {
    public override fun clone(): KotlinParameterSorterSettings = copy()
}

enum class ModifierPositionMode {
    FIRST_PARAMETER,      // Modifier becomes the very first parameter
    FIRST_OPTIONAL        // Modifier after required params, before other optionals (lint-recommended)
}