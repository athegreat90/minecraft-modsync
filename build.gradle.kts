plugins {
    id("modsync.root")
}
allprojects { group = "de.alexandermora.modsync"; version = providers.gradleProperty("releaseVersion").orElse("0.5.0-SNAPSHOT").get() }
sonar {
    properties {
        property("sonar.host.url", providers.gradleProperty("sonar.host.url").orElse("https://sonarqube.alexandermora.de").get())
        property("sonar.projectKey", providers.gradleProperty("sonar.projectKey").orElse("athegreat90_minecraft-modsync").get())
        property("sonar.qualitygate.wait", "true")
        property("sonar.qualitygate.timeout", "300")
        // The Angular application is analysed by the root module: sources, specs and the LCOV report from `npm run test:coverage`.
        property("sonar.sources", "frontend/src/app")
        property("sonar.tests", "frontend/src/app")
        property("sonar.test.inclusions", "frontend/src/app/**/*.spec.ts")
        property("sonar.exclusions", "frontend/src/app/**/*.spec.ts")
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/frontend/lcov.info")
        property("sonar.typescript.tsconfigPaths", "frontend/tsconfig.json,frontend/tsconfig.app.json,frontend/tsconfig.spec.json")
    }
}
dependencies { subprojects.forEach { dokka(project(it.path)) } }
