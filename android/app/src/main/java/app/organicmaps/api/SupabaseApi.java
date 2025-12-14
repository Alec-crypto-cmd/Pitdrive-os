package app.organicmaps.api;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.Header;
import retrofit2.http.POST;

public interface SupabaseApi {
    @POST("auth/v1/token?grant_type=password")
    Call<JsonObject> login(
            @Header("apikey") String apiKey,
            @Body JsonObject body
    );

    @POST("rest/v1/locations")
    Call<Void> logLocation(
            @Header("apikey") String apiKey,
            @Header("Authorization") String token,
            @Header("Prefer") String prefer,
            @Body JsonObject locationData
    );
    @retrofit2.http.GET("rest/v1/locations?select=*")
    Call<java.util.List<JsonObject>> getLocations(
            @Header("apikey") String apiKey,
            @Header("Authorization") String token
    );
}
