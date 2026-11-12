package dev.chr1shaefn3r.accountabilitybuddy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import kotlinx.serialization.Serializable

/** Material 3 with the colours of the user's wallpaper, following the system's light or dark theme. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

@Serializable
internal object Onboarding

@Serializable
internal object Home

@Serializable
internal data class EditLimit(
    val limitId: String? = null,
)

@Serializable
internal object MyBuddies

@Serializable
internal object Invite

@Serializable
internal data class SenderDetail(
    val pairingId: String,
)

@Serializable
internal object Settings

@Composable
fun AppNavigation(app: AccountabilityBuddyApp) {
    val navigation = rememberNavController()
    val back: () -> Unit = { navigation.popBackStack() }
    NavHost(navigation, startDestination = if (app.preferences.isOnboarded) Home else Onboarding) {
        composable<Onboarding> {
            OnboardingScreen(
                app = app,
                onDone = { navigation.navigate(Home) { popUpTo<Onboarding> { inclusive = true } } },
            )
        }
        composable<Home> {
            HomeScreen(
                app = app,
                onEditLimit = { navigation.navigate(EditLimit(it?.value)) },
                onMyBuddies = { navigation.navigate(MyBuddies) },
                onSender = { navigation.navigate(SenderDetail(it.hex)) },
                onSettings = { navigation.navigate(Settings) },
            )
        }
        composable<EditLimit> { entry ->
            EditLimitScreen(app, entry.toRoute<EditLimit>().limitId?.let(::LimitId), back)
        }
        composable<MyBuddies> {
            MyBuddiesScreen(app, onInvite = { navigation.navigate(Invite) }, onBack = back)
        }
        composable<Invite> {
            InviteScreen(app, back)
        }
        composable<SenderDetail> { entry ->
            SenderDetailScreen(app, PairingId(entry.toRoute<SenderDetail>().pairingId), back)
        }
        composable<Settings> {
            SettingsScreen(app, back)
        }
    }
}
