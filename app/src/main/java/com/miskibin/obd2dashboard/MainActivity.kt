package com.miskibin.obd2dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.miskibin.obd2dashboard.ui.DashboardScreen
import com.miskibin.obd2dashboard.ui.theme.Obd2DashboardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { Obd2DashboardApp() }
    }
}

@Composable
private fun Obd2DashboardApp() {
    Obd2DashboardTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            DashboardScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
