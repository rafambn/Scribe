package com.rafambn.scribe.slf4j

/** Selects the single [Slf4jScribe] object that will back SLF4J in this application. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ScribeBackend
