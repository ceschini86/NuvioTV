package com.lagradost.cloudstream3.plugins

/**
 * Annotation that marks a class as a CloudStream plugin entry point.
 * The annotated class should extend [Plugin].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin
