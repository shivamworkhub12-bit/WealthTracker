package com.wealthos.gmail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.wealthos.transaction.TransactionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All Google responses are mocked; synthetic PostgreSQL records are rolled back. */
@SpringBootTest @Transactional
class GmailSyncIntegrationTest {
    static final Path DIR;
    static { try { DIR=Files.createTempDirectory("wealthos-gmail-test-");Files.writeString(DIR.resolve("client.json"),"{\"installed\":{\"client_id\":\"test.apps.googleusercontent.com\",\"client_secret\":\"fictional\"}}"); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry p) {
        p.add("wealthos.gmail.credentials-file",()->DIR.resolve("client.json").toString());p.add("wealthos.gmail.key-file",()->DIR.resolve("key").toString());
        p.add("wealthos.gmail.expected-email",()->"finance@example.test");p.add("wealthos.gmail.sender-domains",()->"examplebank.test");
        p.add("wealthos.gmail.scheduling-enabled",()->"false");p.add("wealthos.gmail.auto-record",()->"true");
    }
    @AfterAll static void cleanup() throws Exception { try(var files=Files.walk(DIR)) { for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    @Autowired GmailStore store;
    @Autowired GmailTokenCipher cipher;
    @Autowired GmailSyncService service;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionRepository transactions;
    @MockitoBean GmailGoogleClient google;
    String id;
    JsonNode metadata,full;
    @BeforeEach void prepare() throws Exception {
        store.connect("finance@example.test",cipher.encrypt("fake-refresh"),GmailSettings.SCOPE,7);
        when(google.refresh(any(),eq("fake-refresh"))).thenReturn(mapper.readTree("{\"access_token\":\"fake-access\",\"expires_in\":3600}"));
        id="synthetic-"+UUID.randomUUID();
        Instant when=Instant.now().minusSeconds(60);
        String date=when.atZone(ZoneId.of("Asia/Kolkata")).format(DateTimeFormatter.ofPattern("dd-MM-uuuu"));
        String ref=UUID.randomUUID().toString().replace("-", "").substring(0,16);
        String body="INR 321.45 debited from A/c XX9876 to TEST CAFE on "+date+" via UPI Ref No "+ref;
        ObjectNode message=mapper.createObjectNode();message.put("id",id);message.put("internalDate",Long.toString(when.toEpochMilli()));
        var payload=message.putObject("payload");payload.put("mimeType","text/plain");
        var headers=payload.putArray("headers");headers.addObject().put("name","From").put("value","Bank <alerts@examplebank.test>");headers.addObject().put("name","Authentication-Results").put("value","mx.google.com; dmarc=pass header.from=examplebank.test");
        metadata=message.deepCopy();payload.putObject("body").put("data",Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)));full=message;
        when(google.message("fake-access",id,false)).thenReturn(metadata);when(google.message("fake-access",id,true)).thenReturn(full);
    }
    JsonNode page(String next) {
        ObjectNode p=mapper.createObjectNode();p.putArray("messages").addObject().put("id",id);if(next!=null) p.put("nextPageToken",next);return p;
    }
    @Test void syncResumesPagesAndOverlappingWindowsWithoutDoubleCounting() {
        long before=transactions.count();
        when(google.messages(eq("fake-access"),anyString(),isNull())).thenReturn(page("page-two"));
        when(google.messages(eq("fake-access"),anyString(),eq("page-two"))).thenReturn(mapper.createObjectNode());
        service.sync();assertEquals(before+1,transactions.count());assertEquals("page-two",store.connection().orElseThrow().pageToken());assertNull(store.connection().orElseThrow().lastSynced());
        service.sync();assertNotNull(store.connection().orElseThrow().lastSynced());assertNull(store.connection().orElseThrow().pageToken());
        when(google.messages(eq("fake-access"),anyString(),isNull())).thenReturn(page(null));service.sync();
        assertEquals(before+1,transactions.count());verify(google,times(1)).message("fake-access",id,true);
        assertEquals(1,store.checked("finance@example.test"));
    }
    @Test void spoofedOrUnapprovedSenderIsNotReadAsPaymentBody() {
        ((ObjectNode)metadata.path("payload").path("headers").get(0)).put("value","alerts@examplebank.test.evil.test");
        when(google.messages(eq("fake-access"),anyString(),isNull())).thenReturn(page(null));
        long before=transactions.count();service.sync();assertEquals(before,transactions.count());verify(google,never()).message("fake-access",id,true);
    }
    @Test void temporaryFailureKeepsCheckpointAndBacksOff() {
        when(google.messages(eq("fake-access"),anyString(),isNull())).thenThrow(new GmailGoogleClient.GoogleFailure(429,false));
        service.sync();var c=store.connection().orElseThrow();assertEquals("ERROR",c.state());assertNotNull(c.windowBefore());assertNull(c.lastSynced());assertTrue(c.retryAfter().isAfter(Instant.now()));
    }
    @Test void revokedAuthorizationStopsAutomaticRetriesUntilReconnect() {
        when(google.refresh(any(),eq("fake-refresh"))).thenThrow(new GmailGoogleClient.GoogleFailure(400,true));
        service.sync();assertEquals("RECONNECT_REQUIRED",store.connection().orElseThrow().state());verify(google,never()).messages(anyString(),anyString(),any());
    }
}
