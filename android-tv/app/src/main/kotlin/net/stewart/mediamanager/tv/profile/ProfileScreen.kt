package net.stewart.mediamanager.tv.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import net.stewart.mediamanager.grpc.ProfileResponse
import net.stewart.mediamanager.grpc.SessionInfo
import net.stewart.mediamanager.grpc.deleteSessionRequest
import net.stewart.mediamanager.grpc.empty
import net.stewart.mediamanager.tv.grpc.GrpcClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfileScreen(
    grpcClient: GrpcClient,
    onBack: () -> Unit = {}
) {
    var profile by remember { mutableStateOf<ProfileResponse?>(null) }
    var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            try {
                profile = grpcClient.withAuth { grpcClient.profileService().getProfile(empty { }) }
                sessions = grpcClient.withAuth { grpcClient.profileService().listSessions(empty { }) }.sessionsList
            } catch (e: Exception) { error = e.message } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Text("Profile", style = MaterialTheme.typography.headlineSmall)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error!!, color = MaterialTheme.colorScheme.error) }
            profile != null -> {
                val p = profile!!

                // Profile info
                Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                    Text(p.username, style = MaterialTheme.typography.headlineMedium)
                    if (p.hasDisplayName()) {
                        Text(p.displayName, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (p.isAdmin) Text("Admin", color = Color(0xFF4CAF50))
                        if (p.hasRatingCeilingLabel()) Text("Rating: ${p.ratingCeilingLabel}")
                    }
                }

                // Sessions
                Text("Active Sessions",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 48.dp, top = 24.dp, bottom = 8.dp))

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions) { session ->
                        Card(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        session.deviceName.ifEmpty { session.type.name.removePrefix("SESSION_TYPE_") },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (session.hasLastUsedAt()) {
                                        val date = Date(session.lastUsedAt.secondsSinceEpoch * 1000)
                                        Text("Last used: ${SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (session.isCurrent) {
                                        Text("Current session", color = Color(0xFF4CAF50),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (!session.isCurrent) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            try {
                                                grpcClient.withAuth {
                                                    grpcClient.profileService().deleteSession(
                                                        deleteSessionRequest {
                                                            sessionId = session.id
                                                            type = session.type
                                                        })
                                                }
                                                refresh()
                                            } catch (_: Exception) { }
                                        }
                                    }) { Text("Revoke") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
