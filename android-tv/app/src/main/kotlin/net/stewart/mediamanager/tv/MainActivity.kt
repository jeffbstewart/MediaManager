package net.stewart.mediamanager.tv

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.Surface
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.image.ImageDiskCache
import net.stewart.mediamanager.tv.image.ImageProvider
import net.stewart.mediamanager.tv.image.ImageStreamClient
import net.stewart.mediamanager.tv.image.LocalImageProvider
import net.stewart.mediamanager.tv.log.LogStreamer
import net.stewart.mediamanager.tv.log.TvLog
import net.stewart.mediamanager.tv.ui.theme.MediaManagerTheme

class MainActivity : ComponentActivity() {
    private var imageProvider: ImageProvider? = null
    private var logStreamer: LogStreamer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authManager = AuthManager(applicationContext)
        val grpcClient = GrpcClient(authManager)
        val imageStreamClient = ImageStreamClient(grpcClient)
        val imageDiskCache = ImageDiskCache(applicationContext)
        val provider = ImageProvider(imageStreamClient, imageDiskCache)
        imageProvider = provider

        TvLog.init(authManager)
        installUncaughtHandler()

        val streamer = LogStreamer(grpcClient, authManager)
        logStreamer = streamer
        streamer.start()

        TvLog.info("app", "app started", mapOf(
            "device_model" to Build.MODEL,
            "android_release" to Build.VERSION.RELEASE
        ))

        // Auto-selected account case: AuthManager.appState() silently activates
        // the sole account; log it here so Binnacle sees the user identity at
        // startup just like an explicit picker selection would.
        if (authManager.getAccountUsernames().size == 1) {
            authManager.activeUsername?.let { username ->
                TvLog.info("auth", "auto-selected sole account '$username'")
            }
        }

        setContent {
            CompositionLocalProvider(LocalImageProvider provides provider) {
                MediaManagerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RectangleShape
                    ) {
                        MediaManagerApp(
                            authManager = authManager,
                            grpcClient = grpcClient
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TvLog.info("app", "app foregrounded")
    }

    override fun onStop() {
        super.onStop()
        TvLog.info("app", "app backgrounded")
    }

    override fun onDestroy() {
        super.onDestroy()
        imageProvider?.shutdown()
        logStreamer?.shutdown()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun installUncaughtHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            TvLog.error("app", "uncaught exception on thread '${thread.name}'", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
