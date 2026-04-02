# Scribe

<div class="hero" markdown>

<div align="center">
  <img src="scribe-logo.svg" alt="Scribe Logo" width="180"/>
</div>

<p align="center">
  <a href="https://kotlinlang.org/docs/multiplatform.html">
    <img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg">
  </a>
  <a href="https://search.maven.org/search?q=g:com.rafambn%20AND%20a:scribe">
    <img alt="Maven Central" src="https://img.shields.io/maven-central/v/com.rafambn/scribe">
  </a>
  <a href="https://opensource.org/license/apache-2-0">
    <img alt="License Apache 2.0" src="https://img.shields.io/badge/License-Apache_2.0-orange.svg">
  </a>
</p>

A Kotlin Multiplatform logging library for structured events and long-lived contextual flows.

</div>

## Why Scribe

Modern systems do not fail in one place. A single user action can cross services, queues, retries, feature flags, and third-party calls. Traditional logging turns that into dozens of partial lines, each easy to write and hard to query.

Scribe is built around the argument from [loggingsucks.com](https://loggingsucks.com/): useful logs are not just structured, they are context-rich. Instead of scattering breadcrumbs everywhere, Scribe gives you primitives for both shapes you actually need:

- `Note` for one-off events that stand on their own (Old way, if you still insist)
- `Scroll` for building a wide event over the lifetime of an operation, then sealing it as a `SealedScroll`

That pushes logging toward "what happened to this request or workflow?" instead of "what line of code ran next?". The result is fewer events, better context, and logs that are easier to filter, correlate, and analyze.

## Read Next

- [Getting Started](getting-started.md) for setup and first usage
- [API Concepts](api-concepts.md) for terminology and data model
- [Lifecycle and Delivery](lifecycle-and-delivery.md) for buffering, margins, and shutdown
- [OpenObserve Showcase](openobserve-showcase.md) for the runnable demo app and local observability stack
