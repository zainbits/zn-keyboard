package dev.zain.znkeyboard.constants

object GifDefaults {
    const val DEFAULT_API_BASE_URL = "https://api.klipy.com"
    const val CONTENT_FILTER = "medium"

    const val CLIENT_MIN_RESULT_COUNT = 8
    const val CLIENT_MAX_RESULT_COUNT = 50
    const val CLIENT_DEFAULT_RESULT_COUNT = 16
    const val CLIENT_DEFAULT_SUGGESTION_COUNT = 8
    const val CLIENT_MAX_SUGGESTION_COUNT = 20
    const val CLIENT_MIN_AUTOCOMPLETE_CHARS = 2
    const val CLIENT_REQUEST_TIMEOUT_MS = 12_000
    const val CLIENT_ERROR_PREVIEW_CHARS = 160
    const val MAX_SHARE_BYTES = 8_000_000L
    const val MAX_PREVIEW_BYTES = 2_500_000L
    const val MAX_PREVIEW_FALLBACK_BYTES = 1_500_000L

    const val CACHE_DIR = "gif_cache"
    const val CACHE_DOWNLOAD_TIMEOUT_MS = 20_000
    const val MAX_CACHED_GIF_BYTES = 8_000_000L
    const val MAX_CACHE_FILES = 40
    const val CACHE_BUFFER_SIZE = 16 * 1024

    const val CARD_SPACING_DP = 8
    const val CARD_RADIUS_DP = 8
    const val FIRST_PAGE = 1
    const val PAGE_SIZE = 24
    const val LOAD_MORE_THRESHOLD_ITEMS = 8
    const val MIN_CELL_HEIGHT_DP = 88
    const val MAX_CELL_HEIGHT_DP = 190
    const val DEFAULT_ASPECT_HEIGHT = 0.8f
    const val SEARCH_RESULT_LIST_HEIGHT_DP = 190
    const val SEARCH_MAX_QUERY_LENGTH = 80
    const val SEARCH_MIN_AUTOCOMPLETE_CHARS = 2
    const val MAX_VISIBLE_SUGGESTIONS = 6
    const val SEARCH_DEBOUNCE_MS = 450L
    const val SEARCH_PLACEHOLDER = "Search KLIPY"
    const val SEARCH_PROMPT = "Type a GIF search and tap Search"
    const val POWERED_BY_LABEL = "Powered by KLIPY"
}
