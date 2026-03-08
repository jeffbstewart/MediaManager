package net.stewart.transcodebuddy

import java.io.File

/**
 * Translates server-provided relative paths to local filesystem paths.
 *
 * The server provides paths like `BLURAY/Movie.mkv` (relative to NAS root).
 * This class resolves them to local paths using the configured NAS mount point.
 */
class PathTranslator(private val nasRoot: String) {

    /**
     * Returns the local source file for a relative path.
     * E.g., `BLURAY/Movie.mkv` -> `\\NAS\Prometheus\prometheus\BLURAY\Movie.mkv`
     */
    fun sourceFile(relativePath: String): File {
        return File(nasRoot, relativePath)
    }

    /**
     * Returns the ForBrowser output path for a source file.
     * E.g., `BLURAY/Movie.mkv` -> `\\NAS\Prometheus\prometheus\ForBrowser\BLURAY\Movie.mp4`
     */
    fun forBrowserPath(relativePath: String): File {
        val relFile = File(relativePath)
        val mp4Name = relFile.nameWithoutExtension + ".mp4"
        val relDir = relFile.parent ?: ""
        return File(nasRoot, "ForBrowser")
            .resolve(relDir)
            .resolve(mp4Name)
    }

    /**
     * Returns the temporary file path used during transcoding.
     * E.g., `BLURAY/Movie.mkv` -> `\\NAS\Prometheus\prometheus\ForBrowser\BLURAY\Movie.tmp`
     */
    fun tmpPath(relativePath: String): File {
        val mp4 = forBrowserPath(relativePath)
        return File(mp4.parentFile, mp4.nameWithoutExtension + ".tmp")
    }
}
