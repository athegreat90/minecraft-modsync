plugins { kotlin("plugin.serialization"); `java-library`; application; id("org.graalvm.buildtools.native") }
dependencies {
    implementation(project(":modsync-protocol"))
    implementation(project(":modsync-scanner"))
    api("info.picocli:picocli:4.7.7")
    implementation(project(":modsync-resolver"))
}
application { mainClass.set("net.modsync.ClientKt") }
graalvmNative { binaries { named("main") { imageName.set("modsync"); buildArgs.add("--no-fallback") } } }
dependencies { testImplementation(project(":modsync-test-fixtures")) }
