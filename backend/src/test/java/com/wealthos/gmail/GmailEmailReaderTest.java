package com.wealthos.gmail;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GmailEmailReaderTest {
    final GmailEmailReader reader=new GmailEmailReader();
    final ObjectMapper mapper=new ObjectMapper();
    ObjectNode part(String type,String body) {
        ObjectNode part=mapper.createObjectNode();part.put("mimeType",type);part.putObject("body").put("data",Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8)));return part;
    }
    @Test void extractsHtmlWithoutExecutingOrFetchingEmbeddedResources() {
        ObjectNode message=mapper.createObjectNode();message.set("payload",part("text/html","<html><head><style>.secret{}</style></head><body><p>INR 30 debited</p><img src='https://example.test/pixel'><script>secret()</script><p>Merchant: Cafe</p></body></html>"));
        String text=reader.text(message).value();assertTrue(text.contains("INR 30 debited"));assertTrue(text.contains("Merchant: Cafe"));assertFalse(text.contains("secret"));assertFalse(text.contains("https://"));
    }
    @Test void prefersPlainTextAndExcludesAttachments() {
        ObjectNode root=mapper.createObjectNode();root.put("mimeType","multipart/alternative");ArrayNode parts=root.putArray("parts");parts.add(part("text/plain","INR 30 debited"));parts.add(part("text/html","<p>INR 999</p>"));parts.add(part("text/plain","private attachment").put("filename","statement.txt"));
        ObjectNode message=mapper.createObjectNode();message.set("payload",root);assertEquals("INR 30 debited",reader.text(message).value());
    }
    @Test void verifiesSenderDomainBoundariesAndGmailAuthentication() throws Exception {
        assertTrue(reader.allowed("alerts@notify.examplebank.test",Set.of("examplebank.test")));
        assertFalse(reader.allowed("alerts@examplebank.test.evil.test",Set.of("examplebank.test")));
        var message=mapper.readTree("{\"payload\":{\"headers\":[{\"name\":\"From\",\"value\":\"Bank <alerts@examplebank.test>\"},{\"name\":\"Authentication-Results\",\"value\":\"mx.google.com; dmarc=pass header.from=examplebank.test\"}]}}");
        assertEquals("alerts@examplebank.test",reader.sender(message));assertTrue(reader.authenticated(message,reader.sender(message)));
        var forged=mapper.readTree("{\"payload\":{\"headers\":[{\"name\":\"Authentication-Results\",\"value\":\"evil.test; dmarc=pass header.from=examplebank.test\"}]}}");
        assertFalse(reader.authenticated(forged,"alerts@examplebank.test"));
    }
    @Test void largeEmailIsMarkedForReview() {
        ObjectNode message=mapper.createObjectNode();message.set("payload",part("text/plain","x".repeat(7000)));var text=reader.text(message);assertEquals(6000,text.value().length());assertTrue(text.truncated());
    }
}
