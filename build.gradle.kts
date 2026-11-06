plugins {
    id("modsync.root")
}
allprojects { group = "de.alexandermora.modsync"; version = providers.gradleProperty("releaseVersion").orElse("0.3.1-SNAPSHOT").get() }
sonar {
    properties {
        property("sonar.host.url", providers.gradleProperty("sonar.host.url").orElse("https://sonarqube.alexandermora.de").get())
        property("sonar.projectKey", providers.gradleProperty("sonar.projectKey").orElse("athegreat90_minecraft-modsync").get())
        property("sonar.qualitygate.wait", "true")
        property("sonar.qualitygate.timeout", "300")
    }
}
dependencies { subprojects.forEach { dokka(it) } }
