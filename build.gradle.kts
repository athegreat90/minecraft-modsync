plugins {
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    kotlin("plugin.spring") version "2.4.21" apply false
    kotlin("plugin.jpa") version "2.4.21" apply false
    id("org.springframework.boot") version "4.1.2" apply false
    id("org.graalvm.buildtools.native") version "0.11.5" apply false
    id("org.sonarqube") version "7.5.0.8588"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("com.diffplug.spotless") version "8.11.0" apply false
    id("org.jetbrains.dokka") version "2.3.0"
}
allprojects { group = "de.alexandermora.modsync"; version = providers.gradleProperty("releaseVersion").orElse("0.2.1-SNAPSHOT").get() }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "jacoco")
    apply(plugin = "org.jetbrains.dokka")
    extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
        dokkaPublications.configureEach { failOnWarning.set(true) }
    }
    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            // Pre-existing dense files stay unformatted until they are rewritten; the OpenAPI generator also parses the DTO files line by line.
            targetExclude("**/domain/Domain.kt", "**/protocol/Protocol.kt", "**/client/Client.kt", "**/client/Installer.kt", "**/auth/Security.kt", "**/inventory/InventoryController.kt")
            ktlint()
            ratchetFrom(providers.gradleProperty("spotless.ratchetFrom").orElse("origin/main").get())
        }
    }
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin", "src/test/kotlin")
        parallel = true
        baseline = file("detekt-baseline.xml")
    }
    apply(plugin = "org.sonarqube")
    extensions.configure<org.sonarqube.gradle.SonarExtension> {
        properties {
            property("sonar.sources", "src/main/kotlin")
            property("sonar.java.binaries", "build/classes/kotlin/main")
            if (file("src/test/kotlin").isDirectory) property("sonar.tests", "src/test/kotlin")

        }
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
    }
    dependencies {
        "implementation"(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
        "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach { reports { xml.required.set(true) } }
    tasks.withType<Test>().configureEach { finalizedBy(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>()) }
    dependencyLocking { lockAllConfigurations() }
}

sonar {
    properties {
        property("sonar.host.url", providers.gradleProperty("sonar.host.url").orElse("https://sonarqube.alexandermora.de").get())
        property("sonar.projectKey", providers.gradleProperty("sonar.projectKey").orElse("athegreat90_minecraft-modsync").get())
        property("sonar.qualitygate.wait", "true")
        property("sonar.qualitygate.timeout", "300")
    }
}
dependencies { subprojects.forEach { dokka(it) } }
