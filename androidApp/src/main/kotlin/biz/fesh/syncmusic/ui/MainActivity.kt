package biz.fesh.syncmusic.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import biz.fesh.syncmusic.data.Config

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val config = Config.get(this)
        setContent {
            // Everything is redrawn when the language changes (tr() reads it while the text is built).
            val language by config.language.collectAsState()
            SyncTheme {
                key(language) { MainScreen() }
            }
        }
    }
}
