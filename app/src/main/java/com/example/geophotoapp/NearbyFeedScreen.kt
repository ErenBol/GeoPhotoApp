package com.example.geophotoapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import java.util.Locale

@Composable
fun AuthenticatedPhoto(path: String, auth: AuthRepository, photos: PhotoRepository, modifier: Modifier, contentScale: ContentScale) {
    val token by auth.token.collectAsState(initial = null)
    val context = LocalContext.current
    val request = remember(path, token) {
        token?.let {
            ImageRequest.Builder(context)
                .data(photos.mediaUrl(path))
                .httpHeaders(NetworkHeaders.Builder().set("Authorization", "Bearer $it").build())
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }
    }
    AsyncImage(model = request, contentDescription = "Nearby photo", modifier = modifier, contentScale = contentScale)
}

@Composable
fun NearbyFeedScreen(auth: AuthRepository, photos: PhotoRepository, location: UnlockLocation, onPhoto: (NearbyPhoto) -> Unit, onCamera: () -> Unit) {
    var feed by remember(location) { mutableStateOf<NearbyFeed?>(null) }
    var error by remember(location) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(location, reload) {
        try {
            feed = photos.nearby(location)
            error = null
        } catch (e: Exception) {
            error = e.message ?: "Could not load nearby photos"
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Nearby feed")
            Button(onClick = onCamera) { Text("Camera") }
        }
        error?.let {
            Text(it)
            Button(onClick = { reload++ }) { Text("Retry") }
        }
        val current = feed
        if (current == null && error == null) Text("Loading nearby photos…")
        if (current != null) {
            if (current.radiusM > 500) Text("No posts within 500m. Showing posts within ${current.radiusM / 1000}km.")
            if (current.photos.isEmpty()) Text("No nearby photos yet. Check back after others publish.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(current.photos, key = { it.id }) { photo ->
                    Card(Modifier.fillMaxWidth().clickable { onPhoto(photo) }) {
                        AuthenticatedPhoto(photo.imageUrl, auth, photos, Modifier.fillMaxWidth().height(260.dp), ContentScale.Crop)
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(String.format(Locale.US, "%.0fm away", photo.distanceM))
                            photo.caption?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text("${photo.likeCount} likes · ${photo.commentCount} comments")
                        }
                    }
                }
            }
        }
    }
}
