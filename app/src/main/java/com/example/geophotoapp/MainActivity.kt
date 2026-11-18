package com.example.geophotoapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
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
        val photos = PhotoRepository(auth)
        setContent {
            GeoPhotoAppTheme {
                val nav = rememberNavController()
                var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
                var latitude by rememberSaveable { mutableStateOf<Double?>(null) }
                var longitude by rememberSaveable { mutableStateOf<Double?>(null) }
                val unlockedLocation by auth.unlockedLocation.collectAsState(initial = null)
                var activeLocation by remember { mutableStateOf<UnlockLocation?>(null) }
                var selectedPhoto by remember { mutableStateOf<NearbyPhoto?>(null) }
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
                        CameraScreen(auth, canOpenFeed = activeLocation != null || unlockedLocation != null, onFeed = {
                            nav.navigate("feed") { launchSingleTop = true }
                        }, onCaptured = { path, lat, lon ->
                            photoPath = path
                            latitude = lat
                            longitude = lon
                            nav.navigate("preview")
                        }, onSignOut = {
                            activeLocation = null
                            nav.navigate("auth") { popUpTo("camera") { inclusive = true } }
                        })
                    }
                    composable("preview") {
                        val path = photoPath
                        val lat = latitude
                        val lon = longitude
                        if (path != null && lat != null && lon != null) {
                            PhotoPreview(path, lat, lon, photos, onRetake = {
                                File(path).delete()
                                photoPath = null
                                nav.popBackStack()
                            }, onPublished = {
                                auth.saveUnlockedLocation(lat, lon)
                                activeLocation = UnlockLocation(lat, lon)
                                File(path).delete()
                                photoPath = null
                                nav.navigate("feed") { popUpTo("camera"); launchSingleTop = true }
                            })
                        } else {
                            LaunchedEffect(Unit) { nav.popBackStack() }
                        }
                    }
                    composable("feed") {
                        val location = activeLocation ?: unlockedLocation
                        if (location != null) NearbyFeedScreen(auth, photos, location, onPhoto = {
                            selectedPhoto = it
                            nav.navigate("detail")
                        }, onCamera = { nav.popBackStack("camera", false) })
                        else LaunchedEffect(Unit) { nav.popBackStack() }
                    }
                    composable("detail") {
                        val photo = selectedPhoto
                        if (photo != null) PhotoDetailScreen(photo, auth, photos, onBack = { nav.popBackStack() })
                        else LaunchedEffect(Unit) { nav.popBackStack() }
                    }
                }
            }
        }
    }
}
