plugins { id("modsync.conventions"); `java-library` }
dependencies {
    api(project(":modsync-domain"))
    implementation("org.tomlj:tomlj:2.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
}
dependencies { testImplementation(project(":modsync-test-fixtures")) }
