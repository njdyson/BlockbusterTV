package com.njdyson.blockbustertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.njdyson.blockbustertv.linklab.LinkLabScreen
import com.njdyson.blockbustertv.ui.AppState
import com.njdyson.blockbustertv.ui.BlockbusterTheme
import com.njdyson.blockbustertv.ui.DetailScreen
import com.njdyson.blockbustertv.ui.HomeScreen
import com.njdyson.blockbustertv.ui.Screen
import com.njdyson.blockbustertv.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    // Bumped every time we come back to the foreground, e.g. after a launched app is closed.
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val app = remember { AppState(applicationContext, scope) }
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            LaunchedEffect(Unit) { app.load() }

            fun open(screen: Screen) { stack.add(screen) }
            BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }

            BlockbusterTheme {
                when (val screen = stack.last()) {
                    Screen.Home -> HomeScreen(
                        app,
                        onOpen = { open(Screen.Detail(it.jwId)) },
                        onSettings = { open(Screen.Settings) },
                    )
                    is Screen.Detail -> app.films[screen.jwId]?.let { DetailScreen(app, it) }
                    Screen.Settings -> SettingsScreen(app, resumeCount.intValue, onLinkLab = { open(Screen.LinkLab) })
                    Screen.LinkLab -> LinkLabScreen(resumeCount = resumeCount.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
    }
}
