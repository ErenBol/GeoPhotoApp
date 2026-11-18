package com.example.geophotoapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun CameraScreen(auth: AuthRepository, canOpenFeed: Boolean, onFeed: () -> Unit, onCaptured: (String, Double, Double) -> Unit, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().build() }
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var locationAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    var captureRequest by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraAllowed = granted
        if (!granted) error = "Camera access is required to take a live photo."
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        locationAllowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationAllowed) captureRequest++
        else error = "Precise location is required. Enable it in Settings if permission was denied."
    }

    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        busy = true
        error = null
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                error = "Precise location permission is required."
                return@LaunchedEffect
            }
            val location = LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            if (location == null) {
                error = "No current location fix. Enable location and try again."
                return@LaunchedEffect
            }
            val file = File.createTempFile("capture_", ".jpg", context.cacheDir)
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        busy = false
                        onCaptured(file.absolutePath, location.latitude, location.longitude)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        busy = false
                        error = "Camera capture failed: ${exception.message}"
                    }
                },
            )
        } catch (e: SecurityException) {
            error = "Precise location permission is required."
            busy = false
        } catch (e: Exception) {
            error = "Could not get a location fix: ${e.message}"
            busy = false
        } finally {
            if (error != null) busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (cameraAllowed) {
            val previewView = remember { PreviewView(context) }
            DisposableEffect(lifecycleOwner, previewView) {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                    } catch (e: Exception) {
                        error = "Camera unavailable: ${e.message}"
                    }
                }, ContextCompat.getMainExecutor(context))
                onDispose { if (future.isDone) future.get().unbindAll() }
            }
            AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        error?.let { Text(it, Modifier.padding(12.dp)) }
        if (cameraAllowed) Text("A precise location fix is attached when you take a photo.", Modifier.padding(horizontal = 12.dp))
        if (cameraAllowed) Button(
            enabled = !busy,
            onClick = {
                if (locationAllowed) captureRequest++
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) { Text(if (busy) "Getting location and capturing…" else "Take photo") }
        if (canOpenFeed) OutlinedButton(onClick = onFeed, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Nearby feed") }
        OutlinedButton(onClick = { scope.launch { auth.signOut(); onSignOut() } }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Sign out")
        }
    }
}
