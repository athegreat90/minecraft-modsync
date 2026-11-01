plugins { `java-library`; application }
dependencies {
    implementation(project(":modsync-client"))
    implementation("org.jline:jline:3.30.6")
    implementation("info.picocli:picocli:4.7.7")
}
application { mainClass.set("net.modsync.InteractiveKt") }
