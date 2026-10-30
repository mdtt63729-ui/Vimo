package com.atube.app.recommendation.shorts

/**
 * Enforces Vimo Shorts PRD's first-launch hard exclusions before candidates enter
 * ranking: Cricket & Sports, Gaming, and Fashion & Beauty.
 *
 * This is deliberately a conservative metadata keyword filter. It does not claim
 * to be semantic classification; unsupported/ambiguous metadata must not be
 * presented as a verified category by this class.
 */
internal object InitialShortsCategoryFilter {
    private val blockedTerms = listOf(
        // Cricket & sports
        "cricket", "ipl", "wpl", "football", "soccer", "basketball", "tennis",
        "badminton", "hockey", "kabaddi", "sports highlights", "match highlights",
        "fifa", "olympics", "wrestling", "boxing", "formula 1", "f1 race",
        // Gaming
        "gaming", "gameplay", "gamer", "minecraft", "free fire", "pubg", "bgmi",
        "call of duty", "valorant", "fortnite", "roblox", "gta v", "esports",
        // Fashion & beauty
        "fashion", "makeup", "beauty", "skincare", "skin care", "cosmetics",
        "outfit ideas", "get ready with me", "grwm", "nail art", "hairstyle",
        "hair styling", "lipstick", "foundation routine", "beauty tips"
    )

    fun isAllowed(title: String?, creator: String?, description: String? = null): Boolean {
        val metadata = listOfNotNull(title, creator, description)
            .joinToString(" ")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        if (metadata.isBlank()) return false
        return blockedTerms.none { term ->
            val normalized = term.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            Regex("(^| )${Regex.escape(normalized)}( |$)").containsMatchIn(metadata)
        }
    }
}
