plugins { `java-library` }
dependencies {
    api(project(":modsync-domain"))
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
dependencies { testImplementation(project(":modsync-test-fixtures")) }
