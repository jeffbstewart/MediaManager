package net.stewart.mediamanager.armeria

import com.github.vokorm.findAll
import com.google.gson.Gson
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import net.stewart.mediamanager.entity.CastMember
import net.stewart.mediamanager.entity.PosterSize
import net.stewart.mediamanager.entity.Title
import net.stewart.mediamanager.entity.WishListItem
import net.stewart.mediamanager.entity.WishStatus
import net.stewart.mediamanager.entity.WishType
import net.stewart.mediamanager.service.TmdbService

/**
 * REST endpoint for actor/person detail page in the Angular web app.
 * Returns person info, owned titles, and TMDB filmography with wish state.
 */
@Blocking
class ActorHttpService {

    private val gson = Gson()
    private val tmdbService = TmdbService()

    @Get("/api/v2/catalog/actor/{personId}")
    fun actorDetail(
        ctx: ServiceRequestContext,
        @Param("personId") personId: Int
    ): HttpResponse {
        val user = ArmeriaAuthDecorator.getUser(ctx)
            ?: return HttpResponse.of(HttpStatus.UNAUTHORIZED)
        val userId = user.id!!

        // Fetch TMDB person details
        val person = tmdbService.fetchPersonDetails(personId)
        if (!person.found) return HttpResponse.of(HttpStatus.NOT_FOUND)

        // Owned titles featuring this actor
        val castEntries = CastMember.findAll().filter { it.tmdb_person_id == personId }
        val allTitles = Title.findAll().associateBy { it.id }
        val ownedTitles = castEntries.mapNotNull { cast ->
            val title = allTitles[cast.title_id] ?: return@mapNotNull null
            if (title.hidden) return@mapNotNull null
            if (!user.canSeeRating(title.content_rating)) return@mapNotNull null
            mapOf(
                "title_id" to title.id,
                "title_name" to title.name,
                "poster_url" to title.posterUrl(PosterSize.THUMBNAIL),
                "release_year" to title.release_year,
                "character_name" to cast.character_name
            )
        }.distinctBy { it["title_id"] }

        // TMDB filmography (other works not in library)
        val ownedTmdbIds = allTitles.values
            .filter { it.tmdb_id != null }
            .map { it.tmdb_id!! to it.media_type }
            .toSet()

        val credits = tmdbService.fetchPersonCredits(personId)

        // User's active media wishes for quick lookup
        val wishedKeys = WishListItem.findAll()
            .filter { it.user_id == userId && it.wish_type == WishType.MEDIA.name && it.status == WishStatus.ACTIVE.name }
            .mapNotNull { it.tmdbKey() }
            .toSet()

        val otherWorks = credits
            .filter { credit ->
                val key = credit.tmdbKey()
                !ownedTmdbIds.contains(key.id to key.type.name)
            }
            .map { credit ->
                val key = credit.tmdbKey()
                mapOf(
                    "tmdb_id" to credit.tmdbId,
                    "title" to credit.title,
                    "media_type" to credit.mediaType,
                    "character_name" to credit.characterName,
                    "release_year" to credit.releaseYear,
                    "poster_path" to credit.posterPath,
                    "popularity" to credit.popularity,
                    "already_wished" to (key in wishedKeys)
                )
            }

        val result = mapOf(
            "person_id" to personId,
            "name" to person.name,
            "biography" to person.biography,
            "birthday" to person.birthday,
            "deathday" to person.deathday,
            "place_of_birth" to person.placeOfBirth,
            "known_for" to person.knownForDepartment,
            "profile_path" to person.profilePath,
            "owned_titles" to ownedTitles,
            "other_works" to otherWorks
        )

        val bytes = gson.toJson(result).toByteArray(Charsets.UTF_8)
        val headers = ResponseHeaders.builder(HttpStatus.OK)
            .contentType(MediaType.JSON_UTF_8)
            .contentLength(bytes.size.toLong())
            .build()
        return HttpResponse.of(headers, HttpData.wrap(bytes))
    }
}
