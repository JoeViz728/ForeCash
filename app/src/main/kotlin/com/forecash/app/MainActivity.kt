package com.forecash.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.forecash.app.ui.ForeCashTheme
import com.forecash.app.ui.screens.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForeCashTheme {
                // For MVP we start with a single top-level screen.
                // It will internally decide: Onboarding vs Calendar view.
                MainScreen()
            }
        }
    }
}
