package com.example.geophotoapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun PhotoPreview(path: String, lat: Double, lon: Double, photos: PhotoRepository, onRetake: () -> Unit, onPublished: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable(path) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Preview & confirm")
        AsyncImage(model = File(path), contentDescription = "Captured live photo", modifier = Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
        OutlinedTextField(caption, { if (it.length <= 500) caption = it }, label = { Text("Caption (optional)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it) }
        Button(enabled = !busy, onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    photos.publish(path, lat, lon, caption.trim().ifEmpty { null })
                    onPublished()
                } catch (e: Exception) {
                    error = e.message ?: "Could not publish photo"
                } finally {
                    busy = false
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Publishing…" else "Publish & Unlock 500m") }
        OutlinedButton(onClick = onRetake, enabled = !busy) { Text("Retake") }
    }
}
