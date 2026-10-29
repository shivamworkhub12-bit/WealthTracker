package com.wealthos.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GmailSecurityTest {
    @TempDir Path dir;
    final ObjectMapper mapper=new ObjectMapper();
    GmailSettings settings;
    GmailGoogleClient google;
    GmailStore store;
    GmailAuthorization auth;
    GmailTokenCipher cipher;
    @BeforeEach void setup() throws Exception {
        Path credentials=dir.resolve("client.json");Files.writeString(credentials,"{\"installed\":{\"client_id\":\"test.apps.googleusercontent.com\",\"client_secret\":\"fictional\"}}");
        settings=new GmailSettings(mapper,"finance@example.test","examplebank.test",true,false,credentials.toString(),dir.resolve("key").toString());
        google=mock(GmailGoogleClient.class);store=mock(GmailStore.class);cipher=new GmailTokenCipher(settings);auth=new GmailAuthorization(settings,google,cipher,store);
    }
    Map<String,String> query(String url) {
        Map<String,String> result=new HashMap<>();
        for(String p:URI.create(url).getRawQuery().split("&")) { String[] kv=p.split("=",2);result.put(URLDecoder.decode(kv[0],java.nio.charset.StandardCharsets.UTF_8),URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8)); }
        return result;
    }
    GmailAuthorization.Launch launch() { return auth.launch(query(auth.start(0)).get("ticket")); }
    @Test void oauthUsesReadOnlyScopePkceAndExpectedAccount() {
        var values=query(launch().url());
        assertEquals(GmailSettings.SCOPE,values.get("scope"));assertEquals("S256",values.get("code_challenge_method"));assertEquals("finance@example.test",values.get("login_hint"));
        assertEquals(GmailSettings.REDIRECT,values.get("redirect_uri"));assertFalse(values.containsKey("client_secret"));
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",GmailAuthorization.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
    @Test void oauthRejectsBrowserMismatchAndReplayedState() {
        var launch=launch();String state=query(launch.url()).get("state");
        assertThrows(GmailAuthorization.ConsentFailure.class,()->auth.finish(state,"code","wrong-browser",null));
        assertThrows(GmailAuthorization.ConsentFailure.class,()->auth.finish(state,"code",launch.cookie(),null));
        verifyNoInteractions(google,store);
    }
    @Test void differentGoogleAccountIsRevokedAndNeverStored() throws Exception {
        var launch=launch();String state=query(launch.url()).get("state");
        when(google.exchange(any(),anyString(),anyString())).thenReturn(mapper.readTree("{\"access_token\":\"fake-access\",\"refresh_token\":\"fake-refresh\",\"scope\":\""+GmailSettings.SCOPE+"\"}"));
        when(google.profile("fake-access")).thenReturn(mapper.readTree("{\"emailAddress\":\"wrong@example.test\"}"));
        assertThrows(GmailAuthorization.ConsentFailure.class,()->auth.finish(state,"code",launch.cookie(),null));
        verify(google).revoke("fake-refresh");verifyNoInteractions(store);
    }
    @Test void tokenStorageIsEncryptedAndAuthenticated() throws Exception {
        String first=cipher.encrypt("fictional-refresh-token"),second=cipher.encrypt("fictional-refresh-token");
        assertNotEquals(first,second);assertFalse(first.contains("fictional"));assertEquals("fictional-refresh-token",cipher.decrypt(first));
        byte[] raw=Base64.getDecoder().decode(first.substring(3));raw[14]^=1;
        assertThrows(IllegalStateException.class,()->cipher.decrypt("v1:"+Base64.getEncoder().encodeToString(raw)));
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("key"))));
    }
    @Test void successfulAuthorizationStoresOnlyEncryptedRefreshToken() throws Exception {
        var launch=launch();String state=query(launch.url()).get("state");
        when(google.exchange(any(),anyString(),anyString())).thenReturn(mapper.readTree("{\"access_token\":\"fake-access\",\"refresh_token\":\"fake-refresh\",\"scope\":\""+GmailSettings.SCOPE+"\"}"));
        when(google.profile("fake-access")).thenReturn(mapper.readTree("{\"emailAddress\":\"finance@example.test\"}"));
        auth.finish(state,"code",launch.cookie(),null);
        verify(store).connect(eq("finance@example.test"),argThat(t->!t.contains("fake-refresh") && t.startsWith("v1:")),eq(GmailSettings.SCOPE),eq(0));
        assertThrows(GmailAuthorization.ConsentFailure.class,()->auth.finish(state,"code",launch.cookie(),null));
    }
}
