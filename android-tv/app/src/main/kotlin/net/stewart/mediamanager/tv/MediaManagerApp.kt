package net.stewart.mediamanager.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.stewart.mediamanager.grpc.MediaType
import net.stewart.mediamanager.tv.auth.AccountPickerScreen
import net.stewart.mediamanager.tv.auth.AppState
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.auth.LoginScreen
import net.stewart.mediamanager.tv.auth.ServerSetupScreen
import net.stewart.mediamanager.tv.catalog.ActorScreen
import net.stewart.mediamanager.tv.catalog.CollectionDetailScreen
import net.stewart.mediamanager.tv.catalog.EpisodesScreen
import net.stewart.mediamanager.tv.catalog.GenreDetailScreen
import net.stewart.mediamanager.tv.catalog.SeasonsScreen
import net.stewart.mediamanager.tv.catalog.TagDetailScreen
import net.stewart.mediamanager.tv.catalog.TitleDetailScreen
import net.stewart.mediamanager.tv.catalog.TitleGridScreen
import net.stewart.mediamanager.tv.grpc.GrpcClient
import net.stewart.mediamanager.tv.home.HomeScreen
import net.stewart.mediamanager.tv.player.VideoPlayerScreen
import net.stewart.mediamanager.tv.search.SearchScreen

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

        // ── Auth flow ────────────────────────────────────────────

        composable("setup") {
            ServerSetupScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onServerConfigured = {
                    navController.navigate("login") { popUpTo("setup") { inclusive = true } }
                }
            )
        }
        composable("login") {
            LoginScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onLoginSuccess = {
                    grpcClient.resetChannel()
                    navController.navigate("home") { popUpTo(0) { inclusive = true } }
                },
                onChangeServer = {
                    authManager.clearServer()
                    grpcClient.resetChannel()
                    navController.navigate("setup") { popUpTo(0) { inclusive = true } }
                },
                onBack = if (authManager.getAccountUsernames().isNotEmpty()) {
                    { navController.popBackStack() }
                } else null
            )
        }
        composable("picker") {
            AccountPickerScreen(
                authManager = authManager,
                onAccountSelected = { username ->
                    authManager.selectAccount(username)
                    grpcClient.resetChannel()
                    navController.navigate("home") { popUpTo(0) { inclusive = true } }
                },
                onAddAccount = { navController.navigate("login") }
            )
        }

        // ── Main content ─────────────────────────────────────────

        composable("home") {
            HomeScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onSwitchAccount = {
                    authManager.deselectAccount()
                    grpcClient.resetChannel()
                    navController.navigate("picker") { popUpTo(0) { inclusive = true } }
                },
                onNavigate = { route -> navController.navigate(route) }
            )
        }

        composable("family") {
            TitleGridScreen(
                mediaType = MediaType.MEDIA_TYPE_PERSONAL,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("movies") {
            TitleGridScreen(
                mediaType = MediaType.MEDIA_TYPE_MOVIE,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("tv") {
            TitleGridScreen(
                mediaType = MediaType.MEDIA_TYPE_TV,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("search") {
            SearchScreen(
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onActorClick = { id -> navController.navigate("actor/$id") },
                onCollectionClick = { id -> navController.navigate("collection/$id") },
                onTagClick = { id -> navController.navigate("tag/$id") },
                onGenreClick = { id -> navController.navigate("genre/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Detail screens ───────────────────────────────────────

        composable("title/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val titleId = it.arguments?.getLong("id") ?: return@composable
            TitleDetailScreen(
                titleId = titleId,
                authManager = authManager,
                grpcClient = grpcClient,
                onActorClick = { id -> navController.navigate("actor/$id") },
                onGenreClick = { id -> navController.navigate("genre/$id") },
                onTagClick = { id -> navController.navigate("tag/$id") },
                onSeasonClick = { id -> navController.navigate("seasons/$id") },
                onCollectionClick = { id -> navController.navigate("collection/$id") },
                onPlay = { tcId -> navController.navigate("play/$tcId") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("seasons/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val titleId = it.arguments?.getLong("id") ?: return@composable
            SeasonsScreen(
                titleId = titleId,
                grpcClient = grpcClient,
                onSeasonClick = { tId, season -> navController.navigate("episodes/$tId/$season") },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            "episodes/{titleId}/{season}",
            arguments = listOf(
                navArgument("titleId") { type = NavType.LongType },
                navArgument("season") { type = NavType.IntType }
            )
        ) {
            val titleId = it.arguments?.getLong("titleId") ?: return@composable
            val season = it.arguments?.getInt("season") ?: return@composable
            EpisodesScreen(
                titleId = titleId,
                seasonNumber = season,
                grpcClient = grpcClient,
                onEpisodeClick = { tcId, epSeason, epNumber ->
                    navController.navigate("play/$tcId/$titleId/$epSeason/$epNumber")
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable("actor/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
            val personId = it.arguments?.getInt("id") ?: return@composable
            ActorScreen(
                tmdbPersonId = personId,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("collection/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) {
            val collId = it.arguments?.getInt("id") ?: return@composable
            CollectionDetailScreen(
                tmdbCollectionId = collId,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("tag/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val tagId = it.arguments?.getLong("id") ?: return@composable
            TagDetailScreen(
                tagId = tagId,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("genre/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            val genreId = it.arguments?.getLong("id") ?: return@composable
            GenreDetailScreen(
                genreId = genreId,
                authManager = authManager,
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        // ── Video player ─────────────────────────────────────────

        // Movie playback (no next-episode)
        composable("play/{transcodeId}", arguments = listOf(
            navArgument("transcodeId") { type = NavType.LongType }
        )) {
            val tcId = it.arguments?.getLong("transcodeId") ?: return@composable
            VideoPlayerScreen(
                transcodeId = tcId,
                authManager = authManager,
                grpcClient = grpcClient,
                onBack = { navController.popBackStack() }
            )
        }

        // Episode playback (with next-episode support)
        composable(
            "play/{transcodeId}/{titleId}/{season}/{episode}",
            arguments = listOf(
                navArgument("transcodeId") { type = NavType.LongType },
                navArgument("titleId") { type = NavType.LongType },
                navArgument("season") { type = NavType.IntType },
                navArgument("episode") { type = NavType.IntType }
            )
        ) {
            val tcId = it.arguments?.getLong("transcodeId") ?: return@composable
            val tId = it.arguments?.getLong("titleId") ?: return@composable
            val s = it.arguments?.getInt("season") ?: return@composable
            val ep = it.arguments?.getInt("episode") ?: return@composable
            VideoPlayerScreen(
                transcodeId = tcId,
                authManager = authManager,
                grpcClient = grpcClient,
                titleId = tId,
                seasonNumber = s,
                episodeNumber = ep,
                onPlayNext = { nextTcId ->
                    // Replace current player with next episode
                    navController.navigate("play/$nextTcId/$tId/$s/${ep + 1}") {
                        popUpTo("play/$tcId/$tId/$s/$ep") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
