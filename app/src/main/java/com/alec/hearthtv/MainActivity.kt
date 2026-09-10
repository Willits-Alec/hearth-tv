package com.alec.hearthtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alec.hearthtv.ui.HearthTvNavHost
import com.alec.hearthtv.ui.HearthTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HearthGraph.init(this)
        enableEdgeToEdge()
        setContent { HearthTvTheme { HearthTvNavHost() } }
    }
}
