# Kotlin guidelines

- **Version:** Kotlin 2.4.21 (declared in the root `build.gradle.kts` plugins block and the Kotlin BOM); JVM toolchain 25. Verified with Spring Boot 4.1.2, Gradle 9.8.2, kotlinx.serialization 1.9.0, Dokka 2.3.0, Detekt 2.0.0-alpha.6 and Spotless/ktlint. GraalVM native-image compatibility is exercised by the release workflow, not locally.
- **Stable features in use:** multi-dollar string interpolation (`$$"..."`), sealed interfaces, data objects, exhaustive `when`, `fun interface`. The `$$` prefix is stable since Kotlin 2.2; do not add experimental compiler flags for it. Use it where literal `$` placeholders matter (Spring `$$"${...}"` property placeholders); prefer serializers over string templates for JSON/YAML.
- **Context parameters / value classes:** not introduced. Identifiers stay `String` UUIDs because they are part of the wire protocol, JPA mappings and native-image configuration; add value classes only together with serializer and persistence tests.
- **Style:** constructor injection, immutable data classes, sealed outcomes for expected failures, no `!!` in new code, narrow `@Suppress` with a justification comment.
- **KDoc:** every new function (public, internal, private, test) documents purpose, parameters, return value, failure outcomes and relevant security or concurrency notes. Changing behavior means updating the KDoc.
- **Tooling:** see `CODE_QUALITY.md`.
