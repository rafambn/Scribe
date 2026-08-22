// Merged into the Gradle-generated karma.conf.js as statements, with `config` in scope.
// Mirrors the 120s mocha timeout configured for Node.js tests in build.gradle.kts;
// without it the default 2000ms per-test timeout kills throughput tests on shared runners.
config.set({
    client: {
        mocha: {
            timeout: 120000
        }
    },
    browserNoActivityTimeout: 300000,
    captureTimeout: 120000
})
