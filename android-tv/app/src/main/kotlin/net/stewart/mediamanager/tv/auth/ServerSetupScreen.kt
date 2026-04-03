package net.stewart.mediamanager.tv.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.TvOutlinedTextField

@Composable
fun ServerSetupScreen(
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onServerConfigured: () -> Unit
) {
    var useTls by remember { mutableStateOf(true) }

    // TLS mode: single host + port
    var tlsHost by remember { mutableStateOf("") }
    var tlsPort by remember { mutableStateOf("8443") }

    // Plaintext mode: single host, separate ports
    var localHost by remember { mutableStateOf("") }
    var grpcPort by remember { mutableStateOf("9090") }
    var httpPort by remember { mutableStateOf("8080") }

    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Two-column layout: branding left, form right — top-aligned to stay above the TV keyboard
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // ── Left: branding + TLS toggle ──
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "Media Manager",
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = "Connect to your server",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Use TLS", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }
        }

        // ── Right: form fields ──
        Column(
            modifier = Modifier
                .weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            val fieldWidth = Modifier.width(360.dp)

            if (useTls) {
                TvOutlinedTextField(
                    value = tlsHost,
                    onValueChange = { tlsHost = it },
                    label = "Hostname",
                    modifier = fieldWidth
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TvOutlinedTextField(
                        value = tlsPort,
                        onValueChange = { tlsPort = it },
                        label = "Port",
                        modifier = Modifier.width(120.dp)
                    )
                    ConnectOrLoading(loading) {
                        if (tlsHost.isBlank()) {
                            error = "Hostname is required"
                            return@ConnectOrLoading
                        }
                        val port = tlsPort.toIntOrNull() ?: 8443
                        scope.launch {
                            loading = true; error = null
                            try {
                                grpcClient.testDiscover(tlsHost.trim(), port, true)
                                authManager.configureTlsServer(tlsHost.trim(), port)
                                grpcClient.resetChannel()
                                onServerConfigured()
                            } catch (e: Exception) {
                                error = "Cannot reach server: ${e.message}"
                            } finally { loading = false }
                        }
                    }
                }
            } else {
                TvOutlinedTextField(
                    value = localHost,
                    onValueChange = { localHost = it },
                    label = "Server IP or hostname",
                    modifier = fieldWidth
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvOutlinedTextField(
                        value = grpcPort,
                        onValueChange = { grpcPort = it },
                        label = "gRPC port",
                        modifier = Modifier.width(120.dp)
                    )
                    TvOutlinedTextField(
                        value = httpPort,
                        onValueChange = { httpPort = it },
                        label = "HTTP port",
                        modifier = Modifier.width(120.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                ConnectOrLoading(loading) {
                    if (localHost.isBlank()) {
                        error = "Server address is required"
                        return@ConnectOrLoading
                    }
                    val gp = grpcPort.toIntOrNull() ?: 9090
                    val hp = httpPort.toIntOrNull() ?: 8080
                    scope.launch {
                        loading = true; error = null
                        try {
                            grpcClient.testDiscover(localHost.trim(), gp, false)
                            authManager.configurePlaintextServer(localHost.trim(), gp, hp)
                            grpcClient.resetChannel()
                            onServerConfigured()
                        } catch (e: Exception) {
                            error = "Cannot reach server: ${e.message}"
                        } finally { loading = false }
                    }
                }
            }

            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectOrLoading(loading: Boolean, onClick: () -> Unit) {
    if (loading) {
        CircularProgressIndicator()
    } else {
        Button(onClick = onClick) {
            Text("Connect")
        }
    }
}
