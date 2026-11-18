package com.example.geophotoapp

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

private val Context.authStore by preferencesDataStore(name = "auth")
private val tokenKey = stringPreferencesKey("access_token")

data class Credentials(val email: String, val password: String)
data class Registration(val email: String, val password: String, val username: String)
data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
)

interface AuthApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body credentials: Credentials): TokenResponse

    @POST("api/v1/auth/register")
    suspend fun register(@Body registration: Registration): TokenResponse
}

class AuthRepository(private val context: Context) {
    private val api = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(AuthApi::class.java)

    val token: Flow<String?> = context.authStore.data.map { it[tokenKey] }

    suspend fun authenticate(email: String, password: String, username: String?) {
        val response = if (username == null) {
            api.login(Credentials(email, password))
        } else {
            api.register(Registration(email, password, username))
        }
        if (response.tokenType != "bearer" || response.accessToken.isBlank()) {
            throw IllegalStateException("Server returned an invalid token")
        }
        context.authStore.edit { it[tokenKey] = response.accessToken }
    }

    suspend fun signOut() {
        context.authStore.edit { it.remove(tokenKey) }
    }
}
