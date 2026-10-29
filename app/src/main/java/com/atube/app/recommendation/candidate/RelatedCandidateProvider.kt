package com.atube.app.recommendation.candidate

import com.atube.app.api.MediaServiceRepository
import com.atube.app.api.obj.StreamItem
import com.atube.app.extensions.toID
import com.atube.app.recommendation.models.CandidateResult
import com.atube.app.recommendation.models.CandidateSource
import com.atube.app.recommendation.models.RecommendationCandidate
import com.atube.app.recommendation.models.RecommendationContext
import com.atube.app.recommendation.models.SourceSignals

/**
 * Provides candidates from related videos of recently watched content.
 *
 * This is extremely important for YouTube-style recommendations.
 * When a user watches an Android tutorial, related candidates
 * might include Android tips, Kotlin tutorials, etc.
 *
 * Uses [MediaServiceRepository.getStreams] to fetch related items
 * from recently watched videos.
 */
class RelatedCandidateProvider : CandidateProvider {

    override val priority: Int = 15

    override suspend fun isAvailable(context: RecommendationContext): Boolean {
        return context.recentlyWatched.isNotEmpty()
    }

    override suspend fun getCandidates(
        context: RecommendationContext,
        page: Int
    ): CandidateResult {
        val recentlyWatched = context.recentlyWatched
        if (recentlyWatched.isEmpty()) {
            return CandidateResult(emptyList(), hasMore = false)
        }

        val candidates = mutableListOf<RecommendationCandidate>()
        val seenIds = mutableSetOf<String>()

        // Fetch related videos for the most recently watched videos
        // Limit to first 3 to avoid excessive network requests
        val videosToProcess = recentlyWatched.take(3)

        for (watched in videosToProcess) {
            try {
                val videoId = watched.url?.toID() ?: continue
                if (videoId.isBlank()) continue

                val streams = MediaServiceRepository.instance.getStreams(videoId)
                val related = streams.relatedStreams

                related.forEach { relatedStream ->
                    val relatedId = relatedStream.url?.toID() ?: return@forEach
                    if (relatedId in seenIds || relatedId in context.displayedIds) {
                        return@forEach
                    }
                    seenIds.add(relatedId)

                    candidates.add(
                        RecommendationCandidate(
                            video = relatedStream,
                            sourceSignals = SourceSignals(
                                isRelated = true,
                                relatednessScore = 0.8f // High relatedness from same video
                            ),
                            source = CandidateSource.RELATED
                        )
                    )
                }
            } catch (e: Exception) {
                // Skip this video's related items if fetch fails
            }
        }

        return CandidateResult(
            candidates = candidates,
            hasMore = false
        )
    }
}
