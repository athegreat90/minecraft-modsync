import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    jacoco
    id("org.jetbrains.dokka")
    id("com.diffplug.spotless")
    id("dev.detekt")
    id("org.sonarqube")
}

dokka {
    dokkaPublications.configureEach { failOnWarning.set(true) }
}
spotless {
    kotlin {
        target("src/**/*.kt")
        // Pre-existing dense files stay unformatted until they are rewritten; the OpenAPI generator also parses the DTO files line by line.
        targetExclude("**/domain/Domain.kt", "**/protocol/Protocol.kt", "**/client/Client.kt", "**/client/Installer.kt", "**/auth/Security.kt", "**/inventory/InventoryController.kt", "**/modrinth/Modrinth.kt")
        ktlint()
        ratchetFrom(providers.gradleProperty("spotless.ratchetFrom").orElse("origin/main").get())
    }
}
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    parallel = true
    baseline = file("detekt-baseline.xml")
}
sonar {
    properties {
        property("sonar.sources", "src/main/kotlin")
        property("sonar.java.binaries", "build/classes/kotlin/main")
        if (file("src/test/kotlin").isDirectory) property("sonar.tests", "src/test/kotlin")
    }
}
kotlin {
    jvmToolchain(25)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}
dependencies {
    "implementation"(platform("org.jetbrains.kotlin:kotlin-bom:2.4.21"))
    "testImplementation"(platform("org.junit:junit-bom:6.1.3"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.4.21")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
    }
    finalizedBy(tasks.withType<JacocoReport>())
}
tasks.withType<JacocoReport>().configureEach {
    reports { xml.required.set(true) }
}
dependencyLocking { lockAllConfigurations() }