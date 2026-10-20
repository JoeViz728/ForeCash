package com.forecash.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.forecash.app.ForeCashApp
import com.forecash.app.data.ForeCashRepository
import com.forecash.app.ui.screens.onboarding.OnboardingScreen
import kotlinx.coroutines.launch

/**
 * Top-level router for MVP.
 *
 * Decision: We strictly require at least one account before showing the calendar.
 * This matches the UX_DESIGN and CRITICAL_DECISIONS.
 */
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as ForeCashApp
    val repository = remember { ForeCashRepository(app.database) }

    var hasAccount by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        hasAccount = repository.getAllAccounts().isNotEmpty()
    }

    when (hasAccount) {
        null -> {
            // Very brief loading state
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        }
        false -> {
            OnboardingScreen(
                onAccountCreated = {
                    scope.launch {
                        hasAccount = true
                    }
                }
            )
        }
        true -> {
            // The real app experience
            CalendarMainScreen(repository = repository)
        }
    }
}
