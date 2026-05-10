package dev.zain.znkeyboard.constants

object AgentDefaults {
    const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
    const val OPENROUTER_API_BASE_URL = "https://openrouter.ai/api/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    const val MAX_REWRITE_SOURCE_CHARS = 12_000
    const val REQUEST_TIMEOUT_MS = 30_000
    const val MIN_REWRITE_LLM_TOKENS = 2_000
    const val MAX_REWRITE_LLM_TOKENS = 6_000
    const val CHARS_PER_OUTPUT_TOKEN_ESTIMATE = 2
    const val ERROR_PREVIEW_CHARS = 160

    const val STRUCTURED_OUTPUT_TEXT_FIELD = "text"
    const val REWRITE_BASE_PROMPT_ASSET = "prompts/rewrite_base.md"
    const val WHATSAPP_PROMPT_ASSET = "prompts/apps/whatsapp.md"
}
