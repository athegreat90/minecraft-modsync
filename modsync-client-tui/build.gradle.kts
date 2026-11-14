plugins { id("modsync.conventions"); `java-library`; application }
dependencies {
    implementation(project(":modsync-client"))
    implementation("org.jline:jline:4.7.0")
    implementation("info.picocli:picocli:4.7.7")
}
application { mainClass.set("de.alexandermora.modsync.client.tui.InteractiveKt") }
