package net.stewart.mediamanager.tv.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.ClientPlatform
import net.stewart.mediamanager.grpc.agreeToTermsRequest
import net.stewart.mediamanager.grpc.getLegalStatusRequest
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.log.TvLog
import net.stewart.mediamanager.tv.ui.components.LegalWebViewDialog

/**
 * Post-login legal-agreement gate. Calls `GetLegalStatus` on entry:
 * - If the server reports the user is already compliant, invokes
 *   [onCompliant] immediately (forwards to home).
 * - Otherwise shows links to the privacy policy and terms of use and
 *   an "I Agree" button that calls `AgreeToTerms` with the required
 *   versions the server just returned.
 *
 * Also entered on cold start after auto-selected login — a silent
 * server-side terms-version bump will re-prompt existing users here.
 */
@Composable
fun LegalAgreementScreen(
    grpcClient: GrpcClient,
    onCompliant: () -> Unit,
    onSignOut: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var privacyUrl by remember { mutableStateOf<String?>(null) }
    var termsUrl by remember { mutableStateOf<String?>(null) }
    var requiredPrivacy by remember { mutableStateOf(0) }
    var requiredTerms by remember { mutableStateOf(0) }
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var webViewTitle by remember { mutableStateOf("") }
    var agreeing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val response = grpcClient.withAuth {
                grpcClient.authService().getLegalStatus(getLegalStatusRequest {
                    platform = ClientPlatform.CLIENT_PLATFORM_ANDROID_TV
                })
            }
            if (response.compliant) {
                onCompliant()
                return@LaunchedEffect
            }
            requiredPrivacy = response.requiredPrivacyPolicyVersion
            requiredTerms = response.requiredTermsOfUseVersion
            privacyUrl = response.privacyPolicyUrl.takeIf { response.hasPrivacyPolicyUrl() && it.isNotBlank() }
            termsUrl = response.termsOfUseUrl.takeIf { response.hasTermsOfUseUrl() && it.isNotBlank() }
            loading = false
        } catch (e: Exception) {
            TvLog.error("legal", "getLegalStatus failed", e)
            error = "Couldn't check agreement status: ${e.message}"
            loading = false
        }
    }

    webViewUrl?.let { url ->
        LegalWebViewDialog(
            url = url,
            title = webViewTitle,
            onDismiss = { webViewUrl = null }
        )
    }

    Box(modifier = Modifier.fillMaxSize().padding(48.dp)) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            error != null -> Column(modifier = Modifier.align(Alignment.Center)) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onSignOut) { Text("Sign Out") }
            }
            else -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Please review and agree",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Continue using Media Manager by agreeing to the following documents.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))

                privacyUrl?.let { url ->
                    OutlinedButton(onClick = {
                        webViewTitle = "Privacy Policy"
                        webViewUrl = url
                    }) {
                        Text("Read Privacy Policy (v$requiredPrivacy)")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                termsUrl?.let { url ->
                    OutlinedButton(onClick = {
                        webViewTitle = "Terms of Use"
                        webViewUrl = url
                    }) {
                        Text("Read Terms of Use (v$requiredTerms)")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (agreeing) {
                        CircularProgressIndicator()
                    } else {
                        Button(onClick = {
                            scope.launch {
                                agreeing = true
                                try {
                                    grpcClient.withAuth {
                                        grpcClient.authService().agreeToTerms(agreeToTermsRequest {
                                            platform = ClientPlatform.CLIENT_PLATFORM_ANDROID_TV
                                            privacyPolicyVersion = requiredPrivacy
                                            termsOfUseVersion = requiredTerms
                                        })
                                    }
                                    TvLog.info("auth", "terms agreed (pp=v$requiredPrivacy, tou=v$requiredTerms)")
                                    onCompliant()
                                } catch (e: Exception) {
                                    TvLog.error("legal", "agreeToTerms failed", e)
                                    error = "Couldn't record agreement: ${e.message}"
                                } finally {
                                    agreeing = false
                                }
                            }
                        }) {
                            Text("I Agree")
                        }
                    }
                    OutlinedButton(onClick = onSignOut) { Text("Sign Out") }
                }
            }
        }
    }
}

