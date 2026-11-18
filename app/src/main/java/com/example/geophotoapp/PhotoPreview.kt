package com.example.geophotoapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun PhotoPreview(path: String, lat: Double, lon: Double, onRetake: () -> Unit) {
    var confirmed by remember(path) { mutableStateOf(false) }
    val image = remember(path) {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview & confirm")
        if (image != null) Image(image, contentDescription = "Captured live photo", modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        else Text("Photo could not be opened")
        Text(String.format(Locale.US, "Latitude: %.6f, Longitude: %.6f", lat, lon))
        if (confirmed) Text("Photo confirmed. Upload and nearby feed arrive in Phase 3.")
        else Button(onClick = { confirmed = true }, enabled = image != null) { Text("Confirm photo") }
        Button(onClick = onRetake) { Text("Retake") }
    }
}
