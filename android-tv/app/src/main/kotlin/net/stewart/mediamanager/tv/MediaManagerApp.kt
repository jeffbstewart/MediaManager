package net.stewart.mediamanager.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.stewart.mediamanager.tv.log.TvLog
import net.stewart.mediamanager.grpc.MediaType
import net.stewart.mediamanager.tv.auth.AccountPickerScreen
import net.stewart.mediamanager.tv.auth.AppState
import net.stewart.mediamanager.tv.auth.AuthManager
import net.stewart.mediamanager.tv.auth.LegalAgreementScreen
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
import net.stewart.mediamanager.tv.live.CamerasScreen
import net.stewart.mediamanager.tv.live.LiveTvScreen
import net.stewart.mediamanager.tv.player.VideoPlayerScreen
import net.stewart.mediamanager.tv.profile.ProfileScreen
import net.stewart.mediamanager.tv.wishlist.WishListScreen
import net.stewart.mediamanager.tv.search.SearchScreen

@Composable
fun MediaManagerApp(authManager: AuthManager, grpcClient: GrpcClient) {
    val initialRoute = remember {
        when (authManager.appState()) {
            AppState.NEEDS_SERVER -> "setup"
            AppState.NEEDS_LOGIN -> "login"
            AppState.PICK_ACCOUNT -> "picker"
            // Run the compliance check on every cold start: a server-side
            // terms-version bump needs to re-prompt existing users.
            AppState.AUTHENTICATED -> "legal"
        }
    }

    val navController = rememberNavController()

    // Navigation events — one write per destination enter. TvLog auto-tags
    // the record with the active username so Binnacle queries can attribute
    // screens to the viewer who opened them.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: "(unknown)"
            TvLog.info("nav", "enter: $route")
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

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
                    // Post-login: gate through legal agreement before home.
                    navController.navigate("legal") { popUpTo(0) { inclusive = true } }
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
                    TvLog.info("auth", "account selected from picker: '$username'")
                    grpcClient.resetChannel()
                    // Post-switch: re-check compliance before the new user sees home.
                    navController.navigate("legal") { popUpTo(0) { inclusive = true } }
                },
                onAddAccount = { navController.navigate("login") }
            )
        }
        composable("legal") {
            LegalAgreementScreen(
                grpcClient = grpcClient,
                onCompliant = {
                    navController.navigate("home") { popUpTo(0) { inclusive = true } }
                },
                onSignOut = {
                    authManager.deselectAccount()
                    grpcClient.resetChannel()
                    val next = if (authManager.getAccountUsernames().isNotEmpty()) "picker" else "login"
                    navController.navigate(next) { popUpTo(0) { inclusive = true } }
                }
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

        composable("wishlist") {
            WishListScreen(
                grpcClient = grpcClient,
                onTitleClick = { id -> navController.navigate("title/$id") },
                onBack = { navController.popBackStack() }
            )
        }

        composable("cameras") {
            CamerasScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onBack = { navController.popBackStack() }
            )
        }

        composable("livetv") {
            LiveTvScreen(
                authManager = authManager,
                grpcClient = grpcClient,
                onBack = { navController.popBackStack() }
            )
        }

        composable("profile") {
            ProfileScreen(
                grpcClient = grpcClient,
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
                // Movies use the same 4-arg route as episodes (season=0,
                // episode=0) so VideoPlayerScreen always has the title id
                // and can resolve a human-readable name.
                onPlay = { tcId -> navController.navigate("play/$tcId/$titleId/0/0") },
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

        // Movies use season=0 / episode=0. Episodes use the real values,
        // which unlocks next-episode lookup inside VideoPlayerScreen.
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
