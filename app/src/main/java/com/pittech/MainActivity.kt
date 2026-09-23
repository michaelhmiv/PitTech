package com.pittech

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.pittech.ui.PitTechApp
import com.pittech.ui.PitTechTheme

class MainActivity : ComponentActivity() {
    private val viewModel: CooksViewModel by viewModels {
        CooksViewModel.Factory((application as PitTechApplication).cookRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PitTechTheme {
                PitTechApp(viewModel)
            }
        }
    }
}
