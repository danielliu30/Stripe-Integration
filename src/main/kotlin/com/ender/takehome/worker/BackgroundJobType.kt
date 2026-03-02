package com.ender.takehome.worker

/**
 * Registry of all background job types.
 *
 * Each entry maps to a [BackgroundJob] implementation that the [SqsWorker]
 * resolves from the Spring context at startup.  Adding a new job is three steps:
 *   1. Add an enum entry here.
 *   2. Create a [BackgroundJob] implementation whose [BackgroundJob.type] returns it.
 *   3. Annotate the implementation with @Component so Spring discovers it.
 */
enum class BackgroundJobType {
    GENERATE_RENT_CHARGES,
}
