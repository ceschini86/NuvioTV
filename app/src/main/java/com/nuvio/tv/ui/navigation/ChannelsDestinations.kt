package com.nuvio.tv.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.nuvio.tv.LocalRootContentInset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.channels.ChannelsScreen
import com.nuvio.tv.ui.screens.channels.ChannelsViewModel
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.ui.screens.home.HomeViewModel

// fork: routes of the Channels section (docs/plan-channels.md).

internal fun NavGraphBuilder.channelsDestinations(
    navController: NavHostController,
    hideBuiltInHeaders: Boolean
) {
    composable(Screen.Channels.route) {
        // Root destination: carries the sidebar inset like NuvioNavHost.rootComposable.
        Box(modifier = Modifier.padding(start = LocalRootContentInset.current)) {
        ChannelsScreen(
            showBuiltInHeader = !hideBuiltInHeaders,
            onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                navController.navigate(
                    Screen.Detail.createRoute(
                        itemId = itemId,
                        itemType = itemType,
                        addonBaseUrl = addonBaseUrl,
                        heroBackdropUrl = HeroBackdropState.consumeAndClear()
                    )
                )
            },
            onNavigateToSeeAll = { catalogId, addonId, type ->
                navController.navigate(Screen.ChannelSeeAll.createRoute(catalogId, addonId, type))
            }
        )
        }
    }

    composable(
        route = Screen.ChannelSeeAll.route,
        arguments = listOf(
            navArgument("catalogId") { type = NavType.StringType },
            navArgument("addonId") { type = NavType.StringType },
            navArgument("type") { type = NavType.StringType }
        )
    ) { backStackEntry ->
        val catalogId = backStackEntry.arguments?.getString("catalogId").orEmpty()
        val addonId = backStackEntry.arguments?.getString("addonId").orEmpty()
        val type = backStackEntry.arguments?.getString("type").orEmpty()
        // Share the Channels screen's view model, so the grid keeps the rows and pages already loaded.
        val channelsEntry = remember { runCatching { navController.getBackStackEntry(Screen.Channels.route) }.getOrNull() }
        val channelsViewModel: ChannelsViewModel = hiltViewModel(channelsEntry ?: backStackEntry)
        val homeEntry = remember { runCatching { navController.getBackStackEntry(Screen.Home.route) }.getOrNull() }
        val homeViewModel: HomeViewModel = hiltViewModel(homeEntry ?: backStackEntry)
        val source = remember(catalogId, addonId, type) { channelsViewModel.rowSource(addonId, type, catalogId) }
        CatalogSeeAllScreen(
            catalogId = catalogId,
            addonId = addonId,
            type = type,
            serverCatalog = source,
            viewModel = homeViewModel,
            onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
            },
            onBackPress = { navController.popBackStack() }
        )
    }
}
