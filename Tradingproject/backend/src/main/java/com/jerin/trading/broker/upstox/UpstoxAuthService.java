package com.jerin.trading.broker.upstox;

import com.jerin.trading.broker.upstox.dto.UpstoxTokenResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class UpstoxAuthService {

    private final RestClient restClient;
    private final UpstoxProperties properties;
    private final UpstoxTokenStore tokenStore;

    public UpstoxAuthService(UpstoxProperties properties, UpstoxTokenStore tokenStore) {
        this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.tokenStore = tokenStore;
    }

    public String buildLoginUrl() {
        return UriComponentsBuilder.fromUriString(properties.baseUrl())
                .path("/v2/login/authorization/dialog")
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.apiKey())
                .queryParam("redirect_uri", properties.redirectUri())
                .build()
                .toUriString();
    }

    public void exchangeCodeForToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", properties.apiKey());
        form.add("client_secret", properties.apiSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("grant_type", "authorization_code");

        UpstoxTokenResponse response = restClient.post()
                .uri("/v2/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(UpstoxTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Upstox token exchange returned no access_token");
        }
        tokenStore.set(response.accessToken());
    }
}
