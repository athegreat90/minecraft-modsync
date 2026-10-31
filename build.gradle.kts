plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.0.9" apply false
    id("org.graalvm.buildtools.native") version "0.11.5" apply false
}
allprojects { group = "net.modsync"; version = providers.gradleProperty("releaseVersion").orElse("0.1.0-SNAPSHOT").get() }
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25) }
    }
    dependencies {
        "implementation"(platform("org.jetbrains.kotlin:kotlin-bom:2.3.21"))
        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.3.21")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform(); testLogging { events("passed", "failed", "skipped") } }
    dependencyLocking { lockAllConfigurations() }
}
