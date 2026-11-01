plugins { kotlin("plugin.serialization"); `java-library`; application; id("org.graalvm.buildtools.native") }
dependencies {
    implementation(project(":modsync-protocol"))
    implementation(project(":modsync-scanner"))
    implementation("info.picocli:picocli:4.7.7")
}
application { mainClass.set("net.modsync.AgentKt") }
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
