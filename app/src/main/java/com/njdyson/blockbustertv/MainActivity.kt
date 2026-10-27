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
import kotlinx.coroutines.delay
import com.njdyson.blockbustertv.linklab.LinkLabScreen
import com.njdyson.blockbustertv.ui.AppState
import com.njdyson.blockbustertv.ui.BlockbusterTheme
import com.njdyson.blockbustertv.ui.BoxSetScreen
import com.njdyson.blockbustertv.ui.DetailScreen
import com.njdyson.blockbustertv.ui.HomeScreen
import com.njdyson.blockbustertv.ui.LinkAccountScreen
import com.njdyson.blockbustertv.ui.PhoneEditScreen
import com.njdyson.blockbustertv.ui.Screen
import com.njdyson.blockbustertv.ui.SearchScreen
import com.njdyson.blockbustertv.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    // Bumped every time we come back to the foreground, e.g. after a launched app is closed.
    private val resumeCount = mutableIntStateOf(0)
    private var app: AppState? = null
    private var stoppedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            val app = remember { AppState(applicationContext, scope).also { this.app = it } }
            val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
            LaunchedEffect(Unit) {
                app.load()
                if (!app.linked) app.server.startSafely()
                // Shelves can change from the phone at any time: fetch data for new films.
                app.shelfStore.shelves.collect { app.fetchMissing(it) }
            }
            // Linked TVs pick up edits from the phone app and other TVs.
            LaunchedEffect(resumeCount.intValue) { app.sync() }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(60_000)
                    app.sync()
                }
            }

            fun open(screen: Screen) { stack.add(screen) }
            fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            BackHandler(enabled = stack.size > 1) { back() }

            BlockbusterTheme {
                when (val screen = stack.last()) {
                    Screen.Home -> HomeScreen(
                        app,
                        onOpen = { open(Screen.Detail(it.jwId)) },
                        onOpenSet = { shelfId, setId -> open(Screen.BoxSet(shelfId, setId)) },
                        onSearch = { open(Screen.Search) },
                        onPhoneEdit = { open(Screen.PhoneEdit) },
                        onSettings = { open(Screen.Settings) },
                    )
                    is Screen.Detail -> app.films[screen.jwId]?.let { DetailScreen(app, it) }
                    is Screen.BoxSet -> BoxSetScreen(app, screen.shelfId, screen.setId, onOpen = { open(Screen.Detail(it.jwId)) })
                    Screen.Search -> SearchScreen(app)
                    Screen.PhoneEdit -> PhoneEditScreen(app, onDone = ::back)
                    Screen.LinkAccount -> LinkAccountScreen(app, onDone = ::back)
                    Screen.Settings -> SettingsScreen(
                        app,
                        resumeCount.intValue,
                        onLinkAccount = { open(Screen.LinkAccount) },
                        onLinkLab = { open(Screen.LinkLab) },
                    )
                    Screen.LinkLab -> LinkLabScreen(resumeCount = resumeCount.intValue)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The phone page is served only while the app is open on the TV, and only until it's linked.
        app?.takeUnless { it.linked }?.server?.startSafely()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
        app?.server?.stop()
    }

    override fun onResume() {
        super.onResume()
        // Coming back after a while: shuffled shelves get a fresh order.
        stoppedAt?.let { app?.reshuffleIfAwayFor(System.currentTimeMillis() - it) }
        stoppedAt = null
        resumeCount.intValue++
    }
}
