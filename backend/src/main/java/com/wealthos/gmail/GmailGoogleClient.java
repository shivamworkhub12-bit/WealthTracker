package com.wealthos.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.*;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

/** Only fixed Google HTTPS endpoints are used. Sensitive response bodies are never logged. */
@Component
public class GmailGoogleClient {
    private final RestClient client;
    public static class GoogleFailure extends RuntimeException {
        final int status;
        final boolean invalidGrant;
        GoogleFailure(int status,boolean invalidGrant) { super("Google authorization or Gmail request failed");this.status=status;this.invalidGrant=invalidGrant; }
    }
    public GmailGoogleClient(RestClient.Builder builder) {
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(20));client=builder.clone().requestFactory(factory).build();
    }
    public JsonNode exchange(GmailSettings.Credentials credentials,String code,String verifier) {
        return token(Map.of("client_id",credentials.clientId(),"client_secret",credentials.clientSecret(),"code",code,"code_verifier",verifier,"redirect_uri",GmailSettings.REDIRECT,"grant_type","authorization_code"));
    }
    public JsonNode refresh(GmailSettings.Credentials credentials,String refreshToken) {
        return token(Map.of("client_id",credentials.clientId(),"client_secret",credentials.clientSecret(),"refresh_token",refreshToken,"grant_type","refresh_token"));
    }
    private JsonNode token(Map<String,String> fields) {
        MultiValueMap<String,String> data=new LinkedMultiValueMap<>();fields.forEach(data::add);
        try { return client.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(data).retrieve().body(JsonNode.class); }
        catch(RestClientResponseException ex) { throw new GoogleFailure(ex.getStatusCode().value(),ex.getResponseBodyAsString().contains("invalid_grant")); }
        catch(RestClientException ex) { throw new GoogleFailure(0,false); }
    }
    public JsonNode profile(String accessToken) { return get("profile",accessToken,Map.of()); }
    public JsonNode messages(String accessToken,String query,String pageToken) {
        Map<String,String> params=new LinkedHashMap<>(Map.of("q",query,"maxResults","100","includeSpamTrash","false"));
        if(pageToken!=null && !pageToken.isBlank()) params.put("pageToken",pageToken);
        return get("messages",accessToken,params);
    }
    public JsonNode message(String accessToken,String id,boolean full) {
        if(!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid Gmail message ID");
        return get("messages/"+id,accessToken,Map.of("format",full?"full":"metadata"));
    }
    private JsonNode get(String path,String accessToken,Map<String,String> params) {
        var uri=UriComponentsBuilder.fromUriString("https://gmail.googleapis.com/gmail/v1/users/me/"+path);
        params.forEach(uri::queryParam);
        try { return client.get().uri(uri.build().encode().toUri()).headers(h->h.setBearerAuth(accessToken)).retrieve().body(JsonNode.class); }
        catch(RestClientResponseException ex) { throw new GoogleFailure(ex.getStatusCode().value(),false); }
        catch(RestClientException ex) { throw new GoogleFailure(0,false); }
    }
    public void revoke(String token) {
        var form=new LinkedMultiValueMap<String,String>();form.add("token",token);
        try { client.post().uri("https://oauth2.googleapis.com/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity(); }
        catch(RestClientException ex) { throw new GoogleFailure(0,false); }
    }
}
