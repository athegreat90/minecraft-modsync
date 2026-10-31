plugins { kotlin("plugin.serialization"); `java-library`; kotlin("plugin.spring"); id("org.springframework.boot") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.9"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(project(":modsync-protocol"))
    implementation(project(":modsync-modrinth"))
    implementation(project(":modsync-resolver"))
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:mariadb:1.21.3")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.6")
}
tasks.jar { enabled = false }
dependencies { testImplementation(project(":modsync-test-fixtures")) }
tasks.bootJar { archiveFileName.set("modsync-server.jar") }
tasks.processResources { from(rootProject.file("docs/architecture/openapi.json")) }
