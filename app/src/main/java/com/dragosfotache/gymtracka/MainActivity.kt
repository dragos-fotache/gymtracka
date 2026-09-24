package com.dragosfotache.gymtracka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dragosfotache.gymtracka.ui.GymApp
import com.dragosfotache.gymtracka.ui.theme.GymTrackaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GymTrackaTheme {
                GymApp()
            }
        }
    }
}
