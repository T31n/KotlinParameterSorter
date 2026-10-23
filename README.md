# Kotlin Parameter Sorter

Group Kotlin function parameters with AI. Invoke **Group parameters with AI** from the `Alt+Enter` menu on a function declaration or call. The plugin updates the function signature and project call sites, adding comments that identify each group.

## Features

- Groups related parameters under descriptive names.
- Can be invoked from a function declaration or call site.
- Reorders arguments and adds matching group comments at project call sites.
- Remembers validated groupings locally and reuses group names/order for matching parameters across related functions.
- The default prompt keeps required parameters before optional parameters and callbacks last, including trailing-lambda callbacks.

## Install and use

1. In your IDE, open **Settings/Preferences | Plugins | Marketplace**, search for **Kotlin Parameter Sorter**, and install it.
2. Open **Settings/Preferences | Tools | Kotlin Parameter Sorter**. Configure the AI API endpoint and model, edit the default system prompt if needed, and enter an API key if your service requires one.
3. In a Kotlin file, place the caret on a function declaration or call, press `Alt+Enter`, and select **Group parameters with AI**.

The endpoint must support the plugin's chat-completions request format. The API key is stored in the IDE Password Safe; local endpoints may not require one.

Validated arrangements are stored in the IDE configuration, separately per project, with a limit of 200 arrangements per project and 2,000 overall. When all parameters are remembered, the plugin reuses their groups without an AI request. For partially remembered functions, the AI receives only the new parameter metadata plus the saved groups as context; the remembered groups keep their names and relative order, with the latest saved assignment winning when records conflict. Use **Clear all remembered arrangements** in the settings page to remove this local memory.

## Data sent to the AI service

The request includes the system prompt, function name, parameter names and types, and whether each parameter has a default value or is a callback. For partially remembered functions, it also includes the saved group names and parameter names needed to place only the new parameters. If every parameter is remembered, the plugin reuses the groups locally without a request. Function bodies and call sites are not sent.

## Support

Report bugs or request features in [GitHub Issues](https://github.com/JackEblan/KotlinParameterSorter/issues).
