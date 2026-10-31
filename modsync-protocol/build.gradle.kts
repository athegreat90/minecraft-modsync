plugins { `java-library`; kotlin("plugin.serialization") }
dependencies {
    api(project(":modsync-domain"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
