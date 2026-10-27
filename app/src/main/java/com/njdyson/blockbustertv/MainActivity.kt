package com.njdyson.blockbustertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.njdyson.blockbustertv.linklab.LinkLabScreen
import com.njdyson.blockbustertv.ui.BlockbusterTheme

class MainActivity : ComponentActivity() {

    // Bumped every time we come back to the foreground, e.g. after a launched app is closed.
    private val resumeCount = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlockbusterTheme {
                LinkLabScreen(resumeCount = resumeCount.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount.intValue++
    }
}
