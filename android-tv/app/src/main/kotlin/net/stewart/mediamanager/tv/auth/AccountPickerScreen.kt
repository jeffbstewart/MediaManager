package net.stewart.mediamanager.tv.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.absoluteValue

private val AVATAR_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFF00838F), Color(0xFF2E7D32),
    Color(0xFF8E24AA), Color(0xFFC62828), Color(0xFFEF6C00),
    Color(0xFF4527A0), Color(0xFF00695C),
)

private fun avatarColor(username: String): Color =
    AVATAR_COLORS[username.hashCode().absoluteValue % AVATAR_COLORS.size]

@Composable
fun AccountPickerScreen(
    authManager: AuthManager,
    onAccountSelected: (String) -> Unit,
    onAddAccount: () -> Unit
) {
    val usernames = authManager.getAccountUsernames()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Text(
                text = "Who's watching?",
                style = MaterialTheme.typography.headlineLarge
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                items(usernames) { username ->
                    AccountCard(
                        username = username,
                        onClick = { onAccountSelected(username) }
                    )
                }
                item {
                    AddAccountCard(onClick = onAddAccount)
                }
            }
        }
    }
}

@Composable
private fun AccountCard(username: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 140.dp, height = 170.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            // Avatar circle with first letter
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(avatarColor(username)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = username.first().uppercase(),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = username,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun AddAccountCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 140.dp, height = 170.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF424242)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "Add Account",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
