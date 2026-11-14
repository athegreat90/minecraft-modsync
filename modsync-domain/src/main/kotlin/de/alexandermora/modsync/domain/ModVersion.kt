package de.alexandermora.modsync.domain

/** Outcome of ordering one version against another; [INCOMPARABLE] means the order cannot be established reliably. */
enum class VersionOrder { OLDER, EQUAL, NEWER, INCOMPARABLE }

/** One comparable piece of a version: a number or a recognized qualifier. */
private sealed interface Token {
    /** A numeric segment. */
    data class Num(val value: Long) : Token

    /** A recognized qualifier word; negative ranks are prereleases and positive ranks are post-releases. */
    data class Qual(val rank: Int) : Token
}

/**
 * A Minecraft-mod version that can be ordered without guessing.
 *
 * Handles semantic versions, dotted numeric versions, `1.21.1-3.2.0` style prefixes and common qualifiers
 * (`snapshot`, `alpha`, `beta`, `milestone`, `rc`, `patch`). Build metadata after `+` is ignored. Anything that
 * cannot be understood is rejected by [parse] so callers report a conflict instead of guessing an order.
 *
 * @property raw Trimmed original text, kept for explanations.
 * @property isPrerelease True when the version carries a prerelease qualifier.
 */
class ModVersion private constructor(val raw: String, private val tokens: List<Token>) {
    val isPrerelease: Boolean = tokens.any { it is Token.Qual && it.rank < 0 }

    /**
     * Orders this version against [other].
     *
     * @param other Version to compare with.
     * @return [VersionOrder.OLDER], [VersionOrder.EQUAL] or [VersionOrder.NEWER] relative to [other], or
     *   [VersionOrder.INCOMPARABLE] when a post-release qualifier meets a numeric segment.
     */
    fun order(other: ModVersion): VersionOrder {
        for (index in 0 until maxOf(tokens.size, other.tokens.size)) {
            val result = compareToken(tokens.getOrNull(index), other.tokens.getOrNull(index))
            if (result != VersionOrder.EQUAL) return result
        }
        return VersionOrder.EQUAL
    }

    override fun toString(): String = raw

    /** Parsing entry point. */
    companion object {
        private val separators = Regex("[.\\-_]")
        private val runs = Regex("[0-9]+|[A-Za-z]+")
        private const val MAX_DIGITS = 18
        private val qualifiers =
            mapOf(
                "snapshot" to -6,
                "dev" to -6,
                "alpha" to -5,
                "a" to -5,
                "beta" to -4,
                "b" to -4,
                "milestone" to -3,
                "m" to -3,
                "preview" to -3,
                "pre" to -3,
                "rc" to -2,
                "cr" to -2,
                "sp" to 1,
                "patch" to 1,
                "post" to 1
            )

        /**
         * Parses a version string.
         *
         * @param raw Version text from mod metadata, or null when it is missing.
         * @return The version, or null when it is blank, a build placeholder or contains unknown words.
         */
        fun parse(raw: String?): ModVersion? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() && "$" !in it }
            val tokens = text?.let { tokenize(it.substringBefore('+').removeLeadingV()) }
            return if (text != null && tokens != null && tokens.any { it is Token.Num }) ModVersion(text, tokens) else null
        }

        private fun String.removeLeadingV(): String = if (length > 1 && (this[0] == 'v' || this[0] == 'V') && this[1].isDigit()) substring(1) else this

        private fun tokenize(text: String): List<Token>? {
            val tokens = mutableListOf<Token>()
            for (part in text.split(separators).filter { it.isNotEmpty() }) {
                val parsed = runs.findAll(part).map { toToken(it.value) }.toList()
                if (parsed.isEmpty() || parsed.any { it == null }) return null
                tokens += parsed.filterNotNull()
            }
            return tokens
        }

        private fun toToken(run: String): Token? =
            if (run[0].isDigit()) {
                if (run.length > MAX_DIGITS) null else Token.Num(run.toLong())
            } else {
                qualifiers[run.lowercase()]?.let { Token.Qual(it) }
            }
    }
}

private fun compareToken(left: Token?, right: Token?): VersionOrder =
    when {
        left == null && right == null -> VersionOrder.EQUAL
        left == null -> invert(compareToken(right, null))
        right == null -> compareAgainstEnd(left)
        left is Token.Num && right is Token.Num -> fromInt(left.value.compareTo(right.value))
        left is Token.Qual && right is Token.Qual -> fromInt(left.rank.compareTo(right.rank))
        left is Token.Qual -> compareQualifierWithNumber(left)
        else -> invert(compareQualifierWithNumber(right as Token.Qual))
    }

/** A token against the end of the other version: zeros are neutral, prereleases sort first, post-releases last. */
private fun compareAgainstEnd(token: Token): VersionOrder =
    when {
        token is Token.Num -> if (token.value == 0L) VersionOrder.EQUAL else VersionOrder.NEWER
        (token as Token.Qual).rank < 0 -> VersionOrder.OLDER
        else -> VersionOrder.NEWER
    }

private fun compareQualifierWithNumber(qualifier: Token.Qual): VersionOrder = if (qualifier.rank < 0) VersionOrder.OLDER else VersionOrder.INCOMPARABLE

private fun fromInt(value: Int): VersionOrder =
    when {
        value < 0 -> VersionOrder.OLDER
        value > 0 -> VersionOrder.NEWER
        else -> VersionOrder.EQUAL
    }

private fun invert(order: VersionOrder): VersionOrder =
    when (order) {
        VersionOrder.OLDER -> VersionOrder.NEWER
        VersionOrder.NEWER -> VersionOrder.OLDER
        else -> order
    }

/**
 * A Maven-style version range such as `[1.21,1.22)` or `[21.1,)`, as used by NeoForge dependency declarations.
 *
 * Bare versions and malformed text are not supported and yield no range, so callers treat compatibility as unknown.
 */
class VersionRange private constructor(private val intervals: List<Interval>) {
    /** One interval with optional bounds. */
    private data class Interval(val lower: ModVersion?, val lowerInclusive: Boolean, val upper: ModVersion?, val upperInclusive: Boolean)

    /**
     * Tests a version against the range.
     *
     * @param version Version to test.
     * @return True or false when decided, or null when the order against a bound cannot be established.
     */
    fun contains(version: ModVersion): Boolean? {
        var unknown = false
        for (interval in intervals) {
            when (interval.contains(version)) {
                true -> return true
                null -> unknown = true
                false -> Unit
            }
        }
        return if (unknown) null else false
    }

    private fun Interval.contains(version: ModVersion): Boolean? {
        val low = lower?.let { bound -> satisfies(version.order(bound), lowerInclusive, wantNewer = true) }
        val high = upper?.let { bound -> satisfies(version.order(bound), upperInclusive, wantNewer = false) }
        val undecided = (lower != null && low == null) || (upper != null && high == null)
        return when {
            low == false || high == false -> false
            undecided -> null
            else -> true
        }
    }

    private fun satisfies(order: VersionOrder, inclusive: Boolean, wantNewer: Boolean): Boolean? =
        when (order) {
            VersionOrder.INCOMPARABLE -> null
            VersionOrder.EQUAL -> inclusive
            VersionOrder.NEWER -> wantNewer
            VersionOrder.OLDER -> !wantNewer
        }

    /** Parsing entry point. */
    companion object {
        private val intervalPattern = Regex("[\\[(][^\\[\\]()]*[\\])]")

        /**
         * Parses a range.
         *
         * @param raw Range text such as `[1.21,1.22)`, `[1.21.1]` or `[1.20,1.21),[1.21.1,)`.
         * @return The range, or null when the text is missing, a bare version or malformed.
         */
        fun parse(raw: String?): VersionRange? {
            val text = raw?.filterNot { it.isWhitespace() }.orEmpty()
            val matches = intervalPattern.findAll(text).map { it.value }.toList()
            val intervals = matches.map(::parseInterval)
            val wellFormed = matches.isNotEmpty() && matches.joinToString(",") == text && intervals.none { it == null }
            return if (wellFormed) VersionRange(intervals.filterNotNull()) else null
        }

        private fun parseInterval(text: String): Interval? {
            val body = text.substring(1, text.length - 1)
            val parts = body.split(',')
            val lowerInclusive = text.first() == '['
            val upperInclusive = text.last() == ']'
            return when (parts.size) {
                1 -> ModVersion.parse(parts[0])?.takeIf { lowerInclusive && upperInclusive }?.let { Interval(it, true, it, true) }
                2 -> boundedInterval(parts, lowerInclusive, upperInclusive)
                else -> null
            }
        }

        private fun boundedInterval(parts: List<String>, lowerInclusive: Boolean, upperInclusive: Boolean): Interval? {
            val lower = parts[0].takeIf { it.isNotEmpty() }?.let { ModVersion.parse(it) ?: return null }
            val upper = parts[1].takeIf { it.isNotEmpty() }?.let { ModVersion.parse(it) ?: return null }
            return Interval(lower, lowerInclusive, upper, upperInclusive)
        }
    }
}
