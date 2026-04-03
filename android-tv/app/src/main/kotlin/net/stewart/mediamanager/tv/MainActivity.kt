package net.stewart.mediamanager.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.tv.material3.Surface
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.theme.MediaManagerTheme
import okhttp3.OkHttpClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authManager = AuthManager(applicationContext)
        val grpcClient = GrpcClient(authManager)

        // Configure Coil to send JWT Bearer auth on image requests
        val authOkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val token = authManager.accessToken
                if (token != null) {
                    val jwt = String(token, Charsets.UTF_8)
                    val authed = original.newBuilder()
                        .header("Authorization", "Bearer $jwt")
                        .build()
                    chain.proceed(authed)
                } else {
                    chain.proceed(original)
                }
            }
            .build()

        SingletonImageLoader.setSafe {
            ImageLoader.Builder(applicationContext)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { authOkHttpClient }))
                }
                .build()
        }

        setContent {
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
