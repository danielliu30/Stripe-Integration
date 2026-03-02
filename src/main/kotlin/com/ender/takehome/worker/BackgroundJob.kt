package com.ender.takehome.worker

/**
 * Strongly-typed background job handler.
 *
 * Implementations declare their [type] and a params class [T].
 * The worker calls [deserialize] to convert the raw JSON map into [T],
 * then [process] to execute the business logic.
 */
interface BackgroundJob<T> {

    /** The job type this handler processes. */
    val type: BackgroundJobType

    /** Convert the raw JSON payload map into a strongly-typed params object. */
    fun deserialize(params: Map<String, Any>): T

    /** Execute the job with the deserialized, typed parameters. */
    fun process(params: T)

    /** Convenience entry point used by the worker to avoid type-erasure issues. */
    fun handleRaw(params: Map<String, Any>) {
        process(deserialize(params))
    }
}
