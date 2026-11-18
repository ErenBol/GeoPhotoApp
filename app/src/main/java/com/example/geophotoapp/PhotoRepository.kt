package com.example.geophotoapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

data class NearbyPhoto(
    val id: String,
    @SerializedName("image_url") val imageUrl: String,
    val caption: String?,
    @SerializedName("distance_m") val distanceM: Double,
    @SerializedName("like_count") val likeCount: Int,
    @SerializedName("comment_count") val commentCount: Int,
)

data class NearbyFeed(@SerializedName("radius_m") val radiusM: Int, val photos: List<NearbyPhoto>)
data class PublishedPhoto(val id: String, val feed: NearbyFeed)
data class PhotoComment(val id: String, val body: String)
data class LikeResult(val liked: Boolean)
private data class UploadResult(@SerializedName("image_url") val imageUrl: String)
private data class PhotoMetadata(val image_url: String, val lat: Double, val lon: Double, val caption: String?)
private data class CommentBody(val body: String)
private data class ReportBody(val reason: String)

private interface PhotoApi {
    @Multipart
    @POST("api/v1/photos/upload-url")
    suspend fun upload(@Header("Authorization") authorization: String, @Part file: MultipartBody.Part): UploadResult

    @POST("api/v1/photos")
    suspend fun publish(@Header("Authorization") authorization: String, @Body photo: PhotoMetadata): PublishedPhoto

    @GET("api/v1/photos/nearby")
    suspend fun nearby(@Header("Authorization") authorization: String, @Query("lat") lat: Double, @Query("lon") lon: Double): NearbyFeed

    @POST("api/v1/photos/{id}/like")
    suspend fun toggleLike(@Header("Authorization") authorization: String, @Path("id") id: String): LikeResult

    @GET("api/v1/photos/{id}/comments")
    suspend fun comments(@Header("Authorization") authorization: String, @Path("id") id: String): List<PhotoComment>

    @POST("api/v1/photos/{id}/comments")
    suspend fun addComment(@Header("Authorization") authorization: String, @Path("id") id: String, @Body body: CommentBody): PhotoComment

    @POST("api/v1/photos/{id}/report")
    suspend fun report(@Header("Authorization") authorization: String, @Path("id") id: String, @Body body: ReportBody)
}

class PhotoRepository(private val auth: AuthRepository) {
    private val api = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PhotoApi::class.java)

    private suspend fun bearer(): String = "Bearer ${auth.token.first() ?: error("Sign in to continue") }"

    fun mediaUrl(path: String): String = BuildConfig.API_BASE_URL.trimEnd('/') + path

    suspend fun publish(path: String, lat: Double, lon: Double, caption: String?): PublishedPhoto {
        val jpeg = withContext(Dispatchers.IO) { compressedJpeg(path) }
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val part = MultipartBody.Part.createFormData("file", "capture.jpg", body)
        val authorization = bearer()
        val upload = api.upload(authorization, part)
        return api.publish(authorization, PhotoMetadata(upload.imageUrl, lat, lon, caption))
    }

    suspend fun nearby(location: UnlockLocation): NearbyFeed = api.nearby(bearer(), location.lat, location.lon)
    suspend fun toggleLike(id: String): LikeResult = api.toggleLike(bearer(), id)
    suspend fun comments(id: String): List<PhotoComment> = api.comments(bearer(), id)
    suspend fun addComment(id: String, body: String): PhotoComment = api.addComment(bearer(), id, CommentBody(body))
    suspend fun report(id: String, reason: String) = api.report(bearer(), id, ReportBody(reason))
}

private fun compressedJpeg(path: String): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Photo could not be opened" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: error("Photo could not be opened")
    val orientation = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    val upright = if (degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    val bytes = ByteArrayOutputStream().use { output ->
        check(upright.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "Photo compression failed" }
        output.toByteArray()
    }
    if (upright !== bitmap) upright.recycle()
    bitmap.recycle()
    require(bytes.size <= 10 * 1024 * 1024) { "Compressed photo exceeds the 10 MB upload limit" }
    return bytes
}
