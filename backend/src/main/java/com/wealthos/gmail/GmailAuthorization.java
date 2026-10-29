package com.wealthos.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GmailAuthorization {
    public record Pending(String state,String verifier,String browserNonce,Instant expires,int lookbackDays) {}
    public record Launch(String url,String cookie) {}
    public static class ConsentFailure extends RuntimeException {
        final String reason;
        ConsentFailure(String reason) { super("Gmail connection was not completed");this.reason=reason; }
    }
    private final GmailSettings settings;
    private final GmailGoogleClient google;
    private final GmailTokenCipher cipher;
    private final GmailStore store;
    private final Map<String,Pending> tickets=new ConcurrentHashMap<>(),states=new ConcurrentHashMap<>();
    public GmailAuthorization(GmailSettings settings,GmailGoogleClient google,GmailTokenCipher cipher,GmailStore store) { this.settings=settings;this.google=google;this.cipher=cipher;this.store=store; }
    static String random() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public String start(int lookbackDays) {
        settings.credentials();
        if(!Set.of(0,7,30).contains(lookbackDays)) throw new IllegalArgumentException("Choose new alerts only, the last 7 days, or the last 30 days");
        tickets.values().removeIf(p->p.expires().isBefore(Instant.now()));states.values().removeIf(p->p.expires().isBefore(Instant.now()));
        if(tickets.size()+states.size()>20) throw new IllegalArgumentException("Too many connection attempts. Wait a few minutes and try again.");
        String ticket=random();tickets.put(ticket,new Pending(random(),random(),random(),Instant.now().plusSeconds(600),lookbackDays));
        return "http://127.0.0.1:8080/api/gmail/authorize?ticket="+ticket;
    }
    public Launch launch(String ticket) {
        Pending p=tickets.remove(ticket);
        if(p==null || p.expires().isBefore(Instant.now())) throw new ConsentFailure("expired");
        states.put(p.state(),p);
        String url=UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("client_id",settings.credentials().clientId()).queryParam("redirect_uri",GmailSettings.REDIRECT)
                .queryParam("response_type","code").queryParam("scope",GmailSettings.SCOPE).queryParam("state",p.state())
                .queryParam("code_challenge",challenge(p.verifier())).queryParam("code_challenge_method","S256")
                .queryParam("access_type","offline").queryParam("prompt","consent").queryParam("login_hint",settings.expectedEmail)
                .build().encode().toUriString();
        return new Launch(url,p.browserNonce());
    }
    public void finish(String state,String code,String cookie,String error) {
        Pending p=state==null?null:states.remove(state);
        if(p==null || p.expires().isBefore(Instant.now()) || cookie==null
                || !MessageDigest.isEqual(p.browserNonce().getBytes(StandardCharsets.US_ASCII),cookie.getBytes(StandardCharsets.US_ASCII))) throw new ConsentFailure("state");
        if(error!=null || code==null || code.isBlank()) throw new ConsentFailure("denied");
        JsonNode token=google.exchange(settings.credentials(),code,p.verifier());
        String access=token.path("access_token").asText(),refresh=token.path("refresh_token").asText();
        if(access.isBlank() || refresh.isBlank()) throw new ConsentFailure("refresh");
        String scope=token.path("scope").asText();
        if(!Set.of(scope.split("\\s+")).equals(Set.of(GmailSettings.SCOPE))) { try { google.revoke(refresh); } catch(RuntimeException ignored) {} throw new ConsentFailure("scope"); }
        String email=google.profile(access).path("emailAddress").asText().toLowerCase(Locale.ROOT);
        if(!settings.expectedEmail.equals(email)) { try { google.revoke(refresh); } catch(RuntimeException ignored) {} throw new ConsentFailure("account"); }
        store.connect(email,cipher.encrypt(refresh),scope,p.lookbackDays());
    }
    public void cancelPending() { tickets.clear();states.clear(); }
}
