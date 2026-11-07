plugins { id("modsync.conventions"); kotlin("plugin.serialization"); `java`; application; id("org.graalvm.buildtools.native") }
dependencies {
    implementation(project(":modsync-protocol"))
    implementation(project(":modsync-scanner"))
    implementation("info.picocli:picocli:4.7.7")
    implementation("io.github.oshai:kotlin-logging-jvm:8.0.6")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("de.alexandermora.modsync.agent.AgentKt") }
graalvmNative {
    binaries {
        named("main") {
            imageName.set("modsync-agent")
            mainClass.set(application.mainClass)
            sharedLibrary.set(false)
            buildArgs.add("--no-fallback")
        }
    }
}
dependencies { testImplementation(project(":modsync-test-fixtures")) }
