package com.salamander.languagellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.salamander.languagellm.ui.theme.LanguageLLMTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WordCoachViewModel by viewModels {
        viewModelFactory {
            initializer {
                WordCoachViewModel(application)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LanguageLLMTheme {
                WordCoachScreen(viewModel = viewModel)
            }
        }
    }
}
