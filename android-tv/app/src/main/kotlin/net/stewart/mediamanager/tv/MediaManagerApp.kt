package net.stewart.mediamanager.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.stewart.mediamanager.tv.auth.AccountPickerScreen
import net.stewart.mediamanager.tv.auth.AppState
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.auth.LoginScreen
import net.stewart.mediamanager.tv.auth.ServerSetupScreen
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.home.HomeScreen

@Composable
fun MediaManagerApp(authManager: AuthManager, grpcClient: GrpcClient) {
    val initialRoute = remember {
        when (authManager.appState()) {
            AppState.NEEDS_SERVER -> "setup"
            AppState.NEEDS_LOGIN -> "login"
            AppState.PICK_ACCOUNT -> "picker"
            AppState.AUTHENTICATED -> "home"
        }
    }

    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = initialRoute) {
        composable("setup") {
            ServerSetupScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onServerConfigured = {
                    navController.navigate("login") {
                        popUpTo("setup") { inclusive = true }
                    }
                }
            )
        }
        composable("login") {
            LoginScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onLoginSuccess = {
                    // After login, account is added and selected — go to home
                    grpcClient.resetChannel()
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onChangeServer = {
                    authManager.clearServer()
                    grpcClient.resetChannel()
                    navController.navigate("setup") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = if (authManager.getAccountUsernames().isNotEmpty()) {
                    {
                        navController.popBackStack()
                    }
                } else null
            )
        }
        composable("picker") {
            AccountPickerScreen(
                authManager = authManager,
                onAccountSelected = { username ->
                    authManager.selectAccount(username)
                    grpcClient.resetChannel()
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onAddAccount = {
                    navController.navigate("login")
                }
            )
        }
        composable("home") {
            HomeScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onSwitchAccount = {
                    authManager.deselectAccount()
                    grpcClient.resetChannel()
                    navController.navigate("picker") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
