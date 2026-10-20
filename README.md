# Kotlin Function Parameter Sorter

An IntelliJ and Android Studio tool for reorganizing Kotlin function parameters. Use the intention action (`Alt+Enter`) from a function declaration or call site; the plugin updates the function and its call sites together.

Choose **Alphabetical** or **AI (group related parameters)** in **Settings | Tools | Kotlin Parameter Sorter**. AI mode sends the function name, parameter names/types, default/callback flags, and composable ordering settings to a configurable OpenAI-compatible chat-completions endpoint. It does not send the function body or call sites. The AI's response is checked to ensure it includes every parameter exactly once; the generated group names are inserted as comments in the signature.

Configure the endpoint, model, and API key in the same settings page. The default endpoint is OpenAI's chat-completions endpoint and the default model is `gpt-4o-mini`. API keys are stored in the IDE Password Safe, not in the plugin settings file. Local endpoints may leave the API key blank.

The AI mode uses the OpenAI-compatible chat-completions request format with bearer-token authentication, so it works with providers and local servers that implement that format. Provider-specific APIs that do not implement it need a compatible endpoint or an adapter. For composable functions, the existing `Modifier` placement setting and callback ordering are retained.
