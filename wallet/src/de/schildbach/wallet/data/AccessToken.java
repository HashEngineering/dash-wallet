package de.schildbach.wallet.data;

import com.squareup.moshi.Json;

public class AccessToken {

    @Json(name = "access_token")
    private String accessToken;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

}
