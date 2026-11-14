package de.alexandermora.modsync.scanner

import de.alexandermora.modsync.domain.ModInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tomlj.Toml
import org.tomlj.TomlTable

/**
 * Reads the mods declared by one JAR metadata file.
 *
 * Understands `META-INF/neoforge.mods.toml`, `META-INF/mods.toml` and `fabric.mod.json`. Pure text parsing:
 * nothing is extracted or executed.
 */
internal object ModMetadataParser {
    private const val JAR_VERSION_PLACEHOLDER = "\${file.jarVersion}"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses one metadata file.
     *
     * @param name Entry name inside the JAR.
     * @param text UTF-8 content of the entry.
     * @param manifestVersion Supplies the `Implementation-Version` of the JAR manifest; only called for a version placeholder.
     * @return The declared mods, empty for entries that declare none.
     * @throws IllegalArgumentException when TOML metadata is malformed.
     */
    fun parse(name: String, text: String, manifestVersion: () -> String?): List<ModInfo> =
        when {
            name.endsWith(".toml") -> parseToml(text, manifestVersion)
            name == "fabric.mod.json" -> parseFabric(text)
            else -> emptyList()
        }

    /**
     * Extracts `Implementation-Version` from manifest text.
     *
     * @param manifest Content of `META-INF/MANIFEST.MF`.
     * @return The trimmed value, or null when absent or blank.
     */
    fun implementationVersion(manifest: String): String? =
        manifest
            .lineSequence()
            .firstOrNull { it.startsWith("Implementation-Version:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun parseToml(text: String, manifestVersion: () -> String?): List<ModInfo> {
        val toml = Toml.parse(text)
        require(!toml.hasErrors()) { "Invalid mod metadata" }
        val declared = toml.getArray("mods") ?: return emptyList()
        val dependencies = toml.getTable("dependencies")
        return (0 until declared.size()).mapNotNull { index ->
            val table: TomlTable = declared.getTable(index)
            table.text("modId")?.let { id ->
                ModInfo(
                    id,
                    resolveVersion(table.text("version"), manifestVersion),
                    dependencyRange(dependencies, id, setOf("minecraft")),
                    dependencyRange(dependencies, id, setOf("neoforge", "forge"))
                )
            }
        }
    }

    private fun resolveVersion(declared: String?, manifestVersion: () -> String?): String? =
        when {
            declared == null -> null
            declared.contains(JAR_VERSION_PLACEHOLDER) -> manifestVersion()
            declared.contains("$") -> null
            else -> declared
        }

    private fun dependencyRange(dependencies: TomlTable?, modId: String, targets: Set<String>): String? {
        val array = dependencies?.getArray(listOf(modId)) ?: return null
        for (index in 0 until array.size()) {
            val table: TomlTable = array.getTable(index)
            if (table.text("modId") in targets) return table.text("versionRange")
        }
        return null
    }

    /** Reads a string key with an explicit nullable type, dropping the checker annotation of the TOML library. */
    private fun TomlTable.text(key: String): String? = getString(key)

    private fun parseFabric(text: String): List<ModInfo> {
        val root = json.parseToJsonElement(text).jsonObject
        val id = root["id"]?.jsonPrimitive?.content ?: return emptyList()
        return listOf(ModInfo(id, root["version"]?.jsonPrimitive?.content?.takeIf { "$" !in it }))
    }
}
