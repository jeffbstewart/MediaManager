package net.stewart.mediamanager.armeria

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.file.HttpFile
import java.nio.file.Path

/**
 * Serves the Angular build mounted under `/app/`.
 *
 * - A path that names a file in [spaDir] is served from disk.
 * - A path with one of the build's asset extensions that names no file
 *   is a 404. This is what a tab from the previous build sees when it
 *   asks for a lazy chunk by its old hashed name after a redeploy; an
 *   index.html answer instead would make the browser's `import()` fail
 *   with a MIME error the client cannot tell from a broken build.
 * - Everything else gets `index.html` so the client-side router owns
 *   the URL.
 */
class SpaService(private val spaDir: Path) : HttpService {
    private val files = FileService.of(spaDir)
    private val index = HttpFile.of(spaDir.resolve("index.html")).asService()

    override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        val mappedPath = ctx.mappedPath().removePrefix("/")
        val file = spaDir.resolve(mappedPath)
        return when {
            mappedPath.isNotEmpty() && file.toFile().isFile -> files.serve(ctx, req)
            looksLikeAsset(mappedPath) -> HttpResponse.of(HttpStatus.NOT_FOUND)
            else -> index.serve(ctx, req)
        }
    }

    private fun looksLikeAsset(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', missingDelimiterValue = "")
        return ext.isNotEmpty() && ext.lowercase() in ASSET_EXTENSIONS
    }

    companion object {
        /** What the Angular build emits, plus what the shell fetches beside it. */
        private val ASSET_EXTENSIONS = setOf(
            "js", "mjs", "map", "css", "json", "webmanifest", "txt", "xml",
            "ico", "png", "jpg", "jpeg", "gif", "svg", "webp", "avif",
            "woff", "woff2", "ttf", "otf", "eot",
        )
    }
}
