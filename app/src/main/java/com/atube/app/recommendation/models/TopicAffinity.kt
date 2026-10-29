package com.atube.app.recommendation.models

/**
 * Represents a topic interest cluster with subtopic breakdown.
 *
 * Groups related concepts (e.g. "creative", "design", "art", "animation")
 * into a broader interest cluster for better recommendations.
 */
data class TopicAffinity(
    val topic: String,
    val score: Double,
    val lastInteraction: Long,
    val interactionCount: Int
) {
    companion object {
        /**
         * Semantic topic clusters — maps keywords to broader interest groups.
         * This enables grouping "design", "art", "animation" under "CREATIVE".
         */
        val TOPIC_CLUSTERS: Map<String, List<String>> = mapOf(
            "creative" to listOf("creative", "design", "art", "animation", "editing",
                "motion", "graphics", "drawing", "painting", "photography", "visual"),
            "android" to listOf("android", "kotlin", "jetpack", "compose", "studio",
                "gradle", "material", "flutter", "dart", "mobile"),
            "gaming" to listOf("gaming", "game", "gameplay", "gamer", "minecraft",
                "fortnite", "valorant", "pubg", "bgmi", "freefire", "esports"),
            "music" to listOf("music", "song", "album", "beat", "rap", "hip",
                "pop", "rock", "jazz", "classical", "lofi", "remix", "dj"),
            "tech" to listOf("tech", "technology", "programming", "coding", "software",
                "python", "javascript", "react", "node", "rust", "golang", "ai",
                "machine", "learning", "data", "cloud", "devops"),
            "ai" to listOf("ai", "artificial", "intelligence", "chatgpt", "gpt",
                "llm", "neural", "deep", "learning", "openai", "gemini", "machine"),
            "sports" to listOf("sports", "cricket", "football", "soccer", "basketball",
                "tennis", "olympic", "match", "ipl", "worldcup"),
            "news" to listOf("news", "breaking", "update", "world", "politics",
                "government", "election", "current"),
            "entertainment" to listOf("entertainment", "movie", "film", "trailer",
                "bollywood", "hollywood", "netflix", "series", "drama", "comedy",
                "funny", "viral"),
            "food" to listOf("food", "cooking", "recipe", "kitchen", "chef",
                "restaurant", "tasty", "cuisine"),
            "travel" to listOf("travel", "vlog", "adventure", "trip", "tour",
                "destination", "explore"),
            "education" to listOf("education", "tutorial", "learn", "course",
                "lesson", "study", "exam", "science", "math", "physics")
        )

        /** Map a keyword to its broader cluster name, or null if no match. */
        fun clusterOf(keyword: String): String? {
            val lower = keyword.lowercase()
            for ((cluster, keywords) in TOPIC_CLUSTERS) {
                if (keywords.any { lower.contains(it) || it.contains(lower) }) {
                    return cluster
                }
            }
            return null
        }

        /** All cluster names. */
        val ALL_CLUSTERS = TOPIC_CLUSTERS.keys.toList()
    }
}
