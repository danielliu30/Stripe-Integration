package com.ender.takehome.worker

/**
 * The message format sent to / received from SQS.
 *
 * [type] is the enum name (e.g. `GENERATE_RENT_CHARGES`).
 * [params] is an arbitrary JSON map that the matching [BackgroundJob]
 * deserializes into its own typed params class.
 */
data class BackgroundJobRequest(
    val type: BackgroundJobType = BackgroundJobType.GENERATE_RENT_CHARGES,
    val params: Map<String, Any> = emptyMap(),
)
