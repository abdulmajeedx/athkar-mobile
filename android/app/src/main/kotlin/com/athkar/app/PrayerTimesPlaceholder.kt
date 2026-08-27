package com.athkar.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Prayer-times screen placeholder. The full feature lives in :feature-prayer-times; this keeps the
 * shell Activity runnable while that module is staged. Reads local data only (single source of truth).
 */
@Composable
fun PrayerTimesPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("أوقات الصلاة", style = MaterialTheme.typography.headlineMedium)
        Text(
            "تظهر أوقات الصلاة وفق موقعك بمجرد المزامنة",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
