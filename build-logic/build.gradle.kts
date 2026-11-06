plugins { `kotlin-dsl` }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.4.21")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.4.21")
    implementation("org.jetbrains.kotlin:kotlin-noarg:2.4.21")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:4.1.2")
    implementation("org.graalvm.buildtools.native:org.graalvm.buildtools.native.gradle.plugin:0.11.5")
    implementation("org.sonarsource.scanner.gradle:sonarqube-gradle-plugin:7.5.0.8588")
    implementation("dev.detekt:detekt-gradle-plugin:2.0.0-alpha.6")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.11.0")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.3.0")
}
