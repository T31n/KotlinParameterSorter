# Kotlin Function Parameter Sorter

An IntelliJ and Android Studio plugin that uses AI to organize Kotlin function parameters. Use the intention action (`Alt+Enter`) from a function declaration or call site; the plugin updates the function and its call sites together.

AI groups related parameters and adds the group names as comments in both the function signature and the corresponding call-site arguments. The plugin reorders arguments at all call sites project-wide. In **Settings | Tools | Kotlin Parameter Sorter**, configure the OpenAI-compatible chat-completions endpoint, model, system prompt, and API key. The endpoint and model fields start empty; the editable system prompt has a default. The AI request includes the system prompt, function name, parameter names/types, and default/callback flags; it does not send the function body or call sites. The AI's response is checked to ensure it includes every parameter exactly once.

API keys are stored in the IDE Password Safe, not in the plugin settings file. Local endpoints may leave the API key blank.

The AI mode uses the OpenAI-compatible chat-completions request format with bearer-token authentication, so it works with providers and local servers that implement that format. Provider-specific APIs that do not implement it need a compatible endpoint or an adapter. The default system prompt asks the AI to keep required parameters before optional parameters and callbacks last, including a final callback used with trailing-lambda syntax.
