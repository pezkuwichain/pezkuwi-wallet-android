package io.novafoundation.nova.runtime.multiNetwork.connection.light

import retrofit2.http.GET
import retrofit2.http.Url

interface LightClientSpecApi {

    /** A file of the config repository, checked against its signed targets by the app's HTTP client. */
    @GET
    suspend fun spec(@Url url: String): String
}
