package net.stewart.mediamanager.tv.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.loginRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.TvOutlinedTextField

@Composable
fun LoginScreen(
    authManager: AuthManager,
    grpcClient: GrpcClient,
    onLoginSuccess: () -> Unit,
    onChangeServer: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // ── Left: branding + secondary actions ──
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "Sign In",
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = authManager.serverHost ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(24.dp))
            if (onBack != null) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onChangeServer) {
                Text("Change Server")
            }
        }

        // ── Right: form fields ──
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.Start
        ) {
            TvOutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username",
                modifier = Modifier.width(360.dp)
            )
            Spacer(Modifier.height(8.dp))
            TvOutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = "Password",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.width(360.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (loading) {
                CircularProgressIndicator()
            } else {
                Button(onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        error = "Username and password are required"
                        return@Button
                    }
                    scope.launch {
                        loading = true
                        error = null
                        try {
                            val response = grpcClient.authService().login(loginRequest {
                                this.username = username.trim()
                                this.password = password
                                this.deviceName = "Android TV"
                            })
                            authManager.addAccount(
                                username = username.trim(),
                                access = response.accessToken.toByteArray(),
                                refresh = response.refreshToken.toByteArray()
                            )
                            onLoginSuccess()
                        } catch (e: Exception) {
                            error = when {
                                e.message?.contains("UNAUTHENTICATED") == true ->
                                    "Invalid username or password"
                                else -> "Login failed: ${e.message}"
                            }
                        } finally {
                            loading = false
                        }
                    }
                }) {
                    Text("Sign In")
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
