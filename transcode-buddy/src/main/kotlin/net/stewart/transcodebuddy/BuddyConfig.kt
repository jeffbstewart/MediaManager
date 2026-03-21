package net.stewart.transcodebuddy

import java.io.File
import java.util.Properties

data class BuddyConfig(
    val serverUrl: String,
    val apiKey: String,
    val buddyName: String,
    val nasRoot: String,
    val ffmpegPath: String,
    val ffprobePath: String,
    val workerCount: Int = 1,
    val encoderPreference: List<String> = listOf("nvenc", "qsv", "cpu"),
    val pollIntervalSeconds: Int = 30,
    val progressIntervalSeconds: Int = 15,
    val whisperPath: String? = null,
    val whisperModel: String = "large-v3-turbo",
    val whisperModelDir: String? = null,
    val whisperLanguage: String = "en",
    val whisperDevice: String = "cuda",
    val whisperComputeType: String = "float16",
    /** Dedicated temp directory for staging source files locally before processing bundles. */
    val localTempDir: String? = null
) {
    companion object {
        fun load(path: String): BuddyConfig {
            val file = File(path)
            require(file.exists()) { "Config file not found: $path" }

            val props = Properties()
            file.inputStream().use { props.load(it) }

            return BuddyConfig(
                serverUrl = requireProp(props, "server_url").trimEnd('/'),
                apiKey = requireProp(props, "api_key"),
                buddyName = requireProp(props, "buddy_name"),
                nasRoot = requireProp(props, "nas_root"),
                ffmpegPath = props.getProperty("ffmpeg_path", defaultFfmpegPath()),
                ffprobePath = props.getProperty("ffprobe_path", defaultFfprobePath()),
                workerCount = props.getProperty("worker_count", "3").toInt().coerceIn(1, 16),
                encoderPreference = props.getProperty("encoder_preference", "nvenc,qsv,cpu")
                    .split(",").map { it.trim().lowercase() },
                pollIntervalSeconds = props.getProperty("poll_interval_seconds", "30").toInt().coerceAtLeast(5),
                progressIntervalSeconds = props.getProperty("progress_interval_seconds", "15").toInt().coerceAtLeast(5),
                whisperPath = props.getProperty("whisper_path"),
                whisperModel = props.getProperty("whisper_model", "large-v3-turbo"),
                whisperModelDir = props.getProperty("whisper_model_dir"),
                whisperLanguage = props.getProperty("whisper_language", "en"),
                whisperDevice = props.getProperty("whisper_device", "cuda"),
                whisperComputeType = props.getProperty("whisper_compute_type", "float16"),
                localTempDir = props.getProperty("local_temp_dir")
            )
        }

        private fun requireProp(props: Properties, key: String): String {
            return props.getProperty(key)
                ?: throw IllegalArgumentException("Required config property '$key' is missing")
        }

        private fun defaultFfmpegPath(): String =
            if (System.getProperty("os.name").lowercase().contains("win"))
                """C:\ffmpeg\bin\ffmpeg.exe""" else "/usr/bin/ffmpeg"

        private fun defaultFfprobePath(): String =
            if (System.getProperty("os.name").lowercase().contains("win"))
                """C:\ffmpeg\bin\ffprobe.exe""" else "/usr/bin/ffprobe"
    }
}
