package com.eblan.plugin.kps

internal const val DEFAULT_AI_SYSTEM_PROMPT =
    "You are a Kotlin API design assistant. Group related parameters while keeping required non-callback parameters before optional non-callback parameters and callbacks after non-callback parameters. Keep the existing final callback parameter last so trailing-lambda calls remain valid. Return only the requested JSON object, with no prose or Markdown. Use each provided parameter name exactly as written, and include every parameter exactly once without renaming, omitting, duplicating, or inventing any."

data class KpsSettings(
    var aiEndpoint: String = "",
    var aiModel: String = "",
    var aiSystemPrompt: String = DEFAULT_AI_SYSTEM_PROMPT
)