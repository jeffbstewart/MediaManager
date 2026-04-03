package net.stewart.mediamanager.tv

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
import net.stewart.mediamanager.tv.ui.theme.MediaManagerTheme

class MainActivity : ComponentActivity() {
    private var imageProvider: ImageProvider? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authManager = AuthManager(applicationContext)
        val grpcClient = GrpcClient(authManager)
        val imageStreamClient = ImageStreamClient(grpcClient)
        val imageDiskCache = ImageDiskCache(applicationContext)
        val provider = ImageProvider(imageStreamClient, imageDiskCache)
        imageProvider = provider

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

    override fun onDestroy() {
        super.onDestroy()
        imageProvider?.shutdown()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
