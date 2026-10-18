package app.odicto.mobile.network

import org.json.JSONObject

/** Per-model request options that trade hidden reasoning for a faster answer. Text in the field never depends on them. */
object ProviderOptions {
    /**
     * Gemini 3 Flash and Pro think at medium or high by default, which can add seconds before the answer. All of them accept
     * "low". Flash-Lite already defaults to the minimum, and older models use a different setting, so both are left alone.
     */
    fun geminiThinking(model: String): JSONObject? =
        if (model.startsWith("gemini-3") && !model.contains("flash-lite")) JSONObject().put("thinkingLevel", "low") else null

    /** Low effort for reasoning models; Laguna XS stays at its own default, as it always has. */
    fun openRouterReasoning(model: String): JSONObject? =
        if (model.startsWith("poolside/laguna-xs-2.1")) null else JSONObject().put("effort", "low").put("exclude", true)
}
