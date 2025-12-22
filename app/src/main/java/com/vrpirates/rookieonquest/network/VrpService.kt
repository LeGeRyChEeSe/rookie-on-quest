package com.vrpirates.rookieonquest.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

data class PublicConfig(
    @SerializedName("baseUri") val baseUri: String,
    @SerializedName("password") val password64: String
)

interface VrpService {
    @GET("downloads/vrp-public.json")
    suspend fun getPublicConfig(): PublicConfig
}
