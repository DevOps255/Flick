package com.flick.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flick.app.ui.home.HomeScreen
import com.flick.app.ui.nearby.NearbyScreen
import com.flick.app.ui.profile.ProfileScreen
import com.flick.app.ui.theme.Indigo
import com.flick.app.ui.theme.Motion
import com.flick.app.ui.theme.pressable
import com.flick.app.ui.transfer.TransferScreen

private const val SLIDE_MS = 280

@Composable
fun FlickApp() {
    val nav = rememberNavController()
    val destination = nav.currentBackStackEntryAsState().value?.destination
    val onHome = destination?.hasRoute<HomeRoute>() == true
    val onNearby = destination?.hasRoute<NearbyRoute>() == true
    val onProfile = destination?.hasRoute<ProfileRoute>() == true

    fun switchTab(route: Any) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(visible = onHome || onNearby || onProfile, enter = fadeIn(Motion.enter(150)), exit = fadeOut(Motion.enter(120))) {
                BottomBar(
                    onHome = onHome,
                    onNearby = onNearby,
                    onProfile = onProfile,
                    onSelect = ::switchTab,
                )
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier = Modifier.fillMaxSize().padding(bottom = inner.calculateBottomPadding()),
            enterTransition = { fadeIn(Motion.enter(150)) },
            exitTransition = { fadeOut(Motion.enter(100)) },
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onSend = { switchTab(NearbyRoute) },
                    onReceive = { switchTab(NearbyRoute) },
                )
            }
            composable<NearbyRoute> { NearbyScreen(onOpenTransfer = { nav.navigate(TransferRoute(it)) }) }
            composable<ProfileRoute> { ProfileScreen() }
            // Enters from the right and leaves to the right, so back feels like the reverse of forward.
            composable<TransferRoute>(
                enterTransition = { slideInHorizontally(tween(SLIDE_MS, easing = Motion.EaseOut)) { it } },
                popExitTransition = { slideOutHorizontally(tween(SLIDE_MS, easing = Motion.EaseOut)) { it } },
            ) {
                TransferScreen(onClose = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun BottomBar(onHome: Boolean, onNearby: Boolean, onProfile: Boolean, onSelect: (Any) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .padding(6.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem("Home", Icons.Outlined.Home, onHome) { onSelect(HomeRoute) }
            BarItem("Nearby", Icons.Outlined.Public, onNearby) { onSelect(NearbyRoute) }
            BarItem("Profile", Icons.Outlined.Person, onProfile) { onSelect(ProfileRoute) }
        }
    }
}

@Composable
private fun BarItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (selected) Indigo else Color.Transparent, RoundedCornerShape(18.dp))
            .pressable(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}
