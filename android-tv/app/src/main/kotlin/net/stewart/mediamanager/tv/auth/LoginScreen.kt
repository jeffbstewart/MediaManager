package net.stewart.mediamanager.tv.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.LegalDocumentInfo
import net.stewart.mediamanager.grpc.loginRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.ui.components.LegalWebViewDialog
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

    // Legal document state
    var legalInfo by remember { mutableStateOf<LegalDocumentInfo?>(null) }
    var acceptedPrivacy by remember { mutableStateOf(false) }
    var acceptedTerms by remember { mutableStateOf(false) }
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var webViewTitle by remember { mutableStateOf("") }

    // Fetch legal info from server on mount
    LaunchedEffect(Unit) {
        try {
            val response = grpcClient.discover()
            if (response.hasLegal()) {
                legalInfo = response.legal
            }
        } catch (_: Exception) {
            // Non-fatal — terms checkboxes just won't appear
        }
    }

    val legal = legalInfo
    val hasPrivacy = legal != null && legal.hasPrivacyPolicyUrl() && legal.privacyPolicyUrl.isNotBlank()
    val hasTerms = legal != null && legal.hasTermsOfUseUrl() && legal.termsOfUseUrl.isNotBlank()
    val needsPrivacy = hasPrivacy && !acceptedPrivacy
    val needsTerms = hasTerms && !acceptedTerms

    // Show WebView dialog when a legal document link is clicked
    webViewUrl?.let { url ->
        LegalWebViewDialog(
            url = url,
            title = webViewTitle,
            onDismiss = { webViewUrl = null }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // -- Left: branding + secondary actions --
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

        // -- Right: form fields --
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

            // -- Legal acceptance checkboxes --
            if (hasPrivacy || hasTerms) {
                Spacer(Modifier.height(12.dp))

                if (hasPrivacy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Checkbox(
                            checked = acceptedPrivacy,
                            onCheckedChange = { acceptedPrivacy = it }
                        )
                        Text(
                            text = "I agree to the ",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Surface(
                            onClick = {
                                webViewTitle = "Privacy Policy"
                                webViewUrl = legal!!.privacyPolicyUrl
                            },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                text = "Privacy Policy",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = TextDecoration.Underline
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }

                if (hasTerms) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 4.dp)
                    ) {
                        Checkbox(
                            checked = acceptedTerms,
                            onCheckedChange = { acceptedTerms = it }
                        )
                        Text(
                            text = "I agree to the ",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Surface(
                            onClick = {
                                webViewTitle = "Terms of Use"
                                webViewUrl = legal!!.termsOfUseUrl
                            },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                text = "Terms of Use",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    textDecoration = TextDecoration.Underline
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (loading) {
                CircularProgressIndicator()
            } else {
                Button(onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        error = "Username and password are required"
                        return@Button
                    }
                    if (needsPrivacy || needsTerms) {
                        error = "Please accept the required agreements"
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
                                this.platform = net.stewart.mediamanager.grpc.ClientPlatform.CLIENT_PLATFORM_ANDROID_TV
                                // Send accepted legal versions
                                if (legal != null) {
                                    if (legal.hasPrivacyPolicyVersion()) {
                                        this.privacyPolicyVersion = legal.privacyPolicyVersion
                                    }
                                    if (legal.hasTermsOfUseVersion()) {
                                        this.termsOfUseVersion = legal.termsOfUseVersion
                                    }
                                }
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
