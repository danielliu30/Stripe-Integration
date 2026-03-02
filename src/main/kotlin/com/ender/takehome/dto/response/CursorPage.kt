package com.ender.takehome.dto.response

/**
 * Cursor-based pagination response.
 *
 * Uses a keyset (startAfterId) pattern instead of offset-based pagination,
 * which avoids the performance and consistency problems of OFFSET/LIMIT.
 *
 * Clients paginate by passing the last item's ID as `startAfterId` on the
 * next request.
 */
data class CursorPage<T>(
    val content: List<T>,
    val hasMore: Boolean,
) {
    companion object {
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 20

        fun sanitizeLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

        fun defaultLimit(): Int = DEFAULT_LIMIT

        /**
         * Build a CursorPage from a list fetched with limit+1 rows.
         * If the list has more than [limit] items, [hasMore] is true and the
         * extra item is trimmed.
         */
        fun <T> of(items: List<T>, limit: Int): CursorPage<T> {
            val hasMore = items.size > limit
            return CursorPage(
                content = if (hasMore) items.take(limit) else items,
                hasMore = hasMore,
            )
        }
    }
}
