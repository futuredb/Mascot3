package com.generativemascot.app.data

import com.generativemascot.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import okhttp3.ResponseBody
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface MascotApi {
    @GET("/v1/cities")
    suspend fun searchCities(@Query("q") query: String): List<CityDto>

    @PUT("/v1/profile/city")
    suspend fun saveCity(@Body body: CityBody): CityDto

    @POST("/v1/mascots")
    suspend fun createMascot(@Header("Idempotency-Key") key: String): MascotDto

    @GET("/v1/mascots/current/active")
    suspend fun activeMascot(): Response<MascotDto>

    @GET("/v1/mascots/library")
    suspend fun mascotLibrary(): List<MascotDto>

    @GET("/v1/mascots/{id}")
    suspend fun getMascot(@Path("id") id: String): MascotDto

    @POST("/v1/mascots/{id}/accept")
    suspend fun accept(@Path("id") id: String): MascotDto

    @POST("/v1/mascots/{id}/activate")
    suspend fun activate(@Path("id") id: String): MascotDto

    @POST("/v1/mascots/{id}/stills/{pack}")
    suspend fun generateStills(@Path("id") id: String, @Path("pack") pack: Int): GenerationPackDto

    @POST("/v1/mascots/{id}/videos/{pack}")
    suspend fun generateVideos(@Path("id") id: String, @Path("pack") pack: Int): GenerationPackDto

    @POST("/v1/mascots/{id}/replace")
    suspend fun replace(@Path("id") id: String, @Header("Idempotency-Key") key: String): MascotDto

    @PATCH("/v1/mascots/{id}")
    suspend fun rename(@Path("id") id: String, @Body body: NameBody): MascotDto

    @GET("/v1/mascots/{id}/manifest")
    suspend fun manifest(@Path("id") id: String): ManifestDto

    @Streaming
    @GET
    suspend fun fetchBytes(@Url url: String): ResponseBody

    @GET("/v1/context/current")
    suspend fun context(@Query("interact") interact: Boolean = false): ContextDto

    @POST("/v1/widgets")
    suspend fun registerWidget(@Body body: WidgetBody)

    @DELETE("/v1/account")
    suspend fun deleteAccount()
}

fun createApi(deviceIdProvider: () -> String): MascotApi {
    val json = Json { ignoreUnknownKeys = true }
    val auth = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("X-Device-Id", deviceIdProvider())
            .apply {
                if (BuildConfig.MASCOT3_CLIENT_TOKEN.isNotBlank()) {
                    header("X-Mascot-Client-Token", BuildConfig.MASCOT3_CLIENT_TOKEN)
                }
            }
            .build()
        chain.proceed(request)
    }
    val client = OkHttpClient.Builder()
        .addInterceptor(auth)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    return Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL + "/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MascotApi::class.java)
}
