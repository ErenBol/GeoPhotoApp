package com.example.geophotoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.geophotoapp.ui.theme.GeoPhotoAppTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = AuthRepository(applicationContext)
        setContent {
            GeoPhotoAppTheme {
                val nav = rememberNavController()
                var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
                var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
                var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
                LaunchedEffect(auth) {
                    auth.token.collect { token ->
                        if (token != null) nav.navigate("camera") {
                            popUpTo("auth") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
                NavHost(navController = nav, startDestination = "auth") {
                    composable("auth") { AuthScreen(auth) }
                    composable("camera") {
                        CameraScreen(auth, onCaptured = { path, lat, lon ->
                            photoPath = path
                            latitude = lat
                            longitude = lon
                            nav.navigate("preview")
                        }, onSignOut = {
                            nav.navigate("auth") { popUpTo("camera") { inclusive = true } }
                        })
                    }
                    composable("preview") {
                        val path = photoPath
                        val lat = latitude
                        val lon = longitude
                        if (path != null && lat != null && lon != null) {
                            PhotoPreview(path, lat, lon, onRetake = {
                                File(path).delete()
                                photoPath = null
                                nav.popBackStack()
                            })
                        } else {
                            LaunchedEffect(Unit) { nav.popBackStack() }
                        }
                    }
                }
            }
        }
    }
}
