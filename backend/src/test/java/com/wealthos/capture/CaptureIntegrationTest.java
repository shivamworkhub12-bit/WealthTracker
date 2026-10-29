package com.wealthos.capture;

import com.wealthos.transaction.TransactionRepository;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs against the local development PostgreSQL; each test rolls back its synthetic records. */
@SpringBootTest @AutoConfigureMockMvc @Transactional
class CaptureIntegrationTest {
    static final Path TOKEN;
    static { try { TOKEN=Files.createTempFile("wealthos-capture-test-", ".token");Files.writeString(TOKEN,"synthetic-token-for-tests-not-a-user-credential"); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry properties) {
        properties.add("wealthos.capture.token-file",TOKEN::toString);
        properties.add("wealthos.capture.allowed-senders",()->"TESTBANK");
        properties.add("wealthos.capture.auto-record",()->"true");
        properties.add("wealthos.gmail.scheduling-enabled",()->"false");
    }
    @AfterAll static void cleanup() throws Exception { Files.deleteIfExists(TOKEN); }
    @Autowired CaptureService service;
    @Autowired TransactionRepository transactions;
    @Autowired MockMvc mvc;
    private final Instant received=Instant.now().minusSeconds(30);
    private String alert(String ref) { return "INR 999.17 debited from A/c XX9876 to TEST CAFE on 29-10-2026 via UPI Ref No "+ref; }
    private String reference() { return UUID.randomUUID().toString().replace("-", "").substring(0,16).toUpperCase(); }

    @Test void sameMessageRetryAndRepeatedReferenceDoNotDoubleCount() {
        String ref=reference(),source=UUID.randomUUID().toString();long before=transactions.count();
        var first=service.ingest(alert(ref),"TESTBANK",source,received,true);
        assertEquals("RECORDED",first.status());assertEquals(before+1,transactions.count());
        var retry=service.ingest(alert(ref),"TESTBANK",source,received,true);
        assertEquals(first.id(),retry.id());assertEquals(first.transactionId(),retry.transactionId());
        var duplicate=service.ingest(alert(ref),"TESTBANK",UUID.randomUUID().toString(),received,true);
        assertEquals("DUPLICATE",duplicate.status());assertEquals(before+1,transactions.count());
    }
    @Test void distinctReferencesPreserveTwoIdenticalPurchases() {
        long before=transactions.count();
        assertEquals("RECORDED",service.ingest(alert(reference()),"TESTBANK",UUID.randomUUID().toString(),received,true).status());
        assertEquals("RECORDED",service.ingest(alert(reference()),"TESTBANK",UUID.randomUUID().toString(),received,true).status());
        assertEquals(before+2,transactions.count());
    }
    @Test void uncertainAlertsStayOutOfSpendingTotals() {
        long before=transactions.count();
        assertEquals("REVIEW",service.ingest("INR 999.17 spent at TEST CAFE","TESTBANK",UUID.randomUUID().toString(),received,true).status());
        assertEquals("IGNORED",service.ingest("OTP 123456 for INR 999.17 payment","TESTBANK",UUID.randomUUID().toString(),received,true).status());
        assertEquals(before,transactions.count());
    }
    @Test void conflictingReferenceDetailsAreSentForReview() {
        String ref=reference();
        service.ingest(alert(ref),"TESTBANK",UUID.randomUUID().toString(),received,true);
        var conflict=service.ingest(alert(ref).replace("999.17","888.16"),"TESTBANK",UUID.randomUUID().toString(),received,true);
        assertEquals("REVIEW",conflict.status());assertNull(conflict.transactionId());
    }
    @Test void emailAndSmsForTheSamePaymentAreDeduplicated() {
        String ref=reference();long before=transactions.count();
        var sms=service.ingest(alert(ref),"TESTBANK",UUID.randomUUID().toString(),received,true);
        var email=service.ingestEmail(alert(ref),"bank@example.test",UUID.randomUUID().toString(),received,true,true);
        assertEquals("DUPLICATE",email.status());assertEquals(sms.transactionId(),email.transactionId());assertEquals(before+1,transactions.count());
    }
    @Test void unverifiedEmailNeverRecordsAutomatically() {
        long before=transactions.count();
        var email=service.ingestEmail(alert(reference()),"bank@example.test",UUID.randomUUID().toString(),received,false,true);
        assertEquals("REVIEW",email.status());assertEquals("GMAIL",email.source());assertEquals(before,transactions.count());
    }
    @Test void incomingRequiresTokenAndApprovedSender() throws Exception {
        String payload="{\"text\":\"INR 1 debited\",\"sender\":\"UNAPPROVED\",\"sourceId\":\"test\",\"receivedAt\":\"2026-10-29T11:00:00Z\"}";
        mvc.perform(post("/api/capture/incoming").contentType("application/json").content(payload)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/capture/incoming").header("Authorization","Bearer synthetic-token-for-tests-not-a-user-credential").contentType("application/json").content(payload)).andExpect(status().isForbidden());
    }
}
