package com.example.geophotoapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PhotoDetailScreen(photo: NearbyPhoto, auth: AuthRepository, photos: PhotoRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var comments by remember(photo.id) { mutableStateOf<List<PhotoComment>>(emptyList()) }
    var commentText by remember(photo.id) { mutableStateOf("") }
    var likeCount by remember(photo.id) { mutableStateOf(photo.likeCount) }
    var liked by remember(photo.id) { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var reported by remember { mutableStateOf(false) }
    LaunchedEffect(photo.id) {
        try { comments = photos.comments(photo.id) }
        catch (e: Exception) { error = e.message ?: "Could not load comments" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("Back") }
            TextButton(onClick = { menuOpen = true }) { Text("More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Report post") }, onClick = { menuOpen = false; reportOpen = true })
            }
        }
        AuthenticatedPhoto(photo.imageUrl, auth, photos, Modifier.fillMaxWidth().height(300.dp), ContentScale.Fit)
        photo.caption?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = photos.toggleLike(photo.id)
                        liked = result.liked
                        likeCount = (likeCount + if (result.liked) 1 else -1).coerceAtLeast(0)
                        error = null
                    } catch (e: Exception) { error = e.message ?: "Could not update like" }
                    finally { busy = false }
                }
            }) { Text("${when (liked) { true -> "Unlike"; false -> "Like"; null -> "Toggle like" }} · $likeCount") }
            Text("${comments.size} comments")
        }
        error?.let { Text(it) }
        if (reported) Text("Report submitted")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(comments, key = { it.id }) { comment -> Text(comment.body) }
        }
        OutlinedTextField(commentText, { if (it.length <= 2000) commentText = it }, label = { Text("Add a comment") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && commentText.isNotBlank(), onClick = {
            busy = true
            scope.launch {
                try {
                    val added = photos.addComment(photo.id, commentText.trim())
                    comments = comments + added
                    commentText = ""
                    error = null
                } catch (e: Exception) { error = e.message ?: "Could not add comment" }
                finally { busy = false }
            }
        }) { Text("Post comment") }
    }
    if (reportOpen) AlertDialog(
        onDismissRequest = { reportOpen = false },
        title = { Text("Report post") },
        text = { OutlinedTextField(reportReason, { if (it.length <= 500) reportReason = it }, label = { Text("Reason") }) },
        confirmButton = {
            TextButton(enabled = !busy && reportReason.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        photos.report(photo.id, reportReason.trim())
                        reported = true
                        reportOpen = false
                        reportReason = ""
                        error = null
                    } catch (e: Exception) { error = e.message ?: "Could not submit report" }
                    finally { busy = false }
                }
            }) { Text("Submit") }
        },
        dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel") } },
    )
}
