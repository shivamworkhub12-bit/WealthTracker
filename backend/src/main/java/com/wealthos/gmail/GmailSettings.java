package com.wealthos.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GmailSettings {
    public static final String SCOPE="https://www.googleapis.com/auth/gmail.readonly";
    public static final String REDIRECT="http://127.0.0.1:8080/api/gmail/oauth/callback";
    final String expectedEmail;
    final Set<String> senderDomains;
    final boolean autoRecord;
    final boolean schedulingEnabled;
    final Path credentialsFile;
    final Path keyFile;
    private final ObjectMapper mapper;
    public record Credentials(String clientId,String clientSecret) {}
    public GmailSettings(ObjectMapper mapper,
            @Value("${wealthos.gmail.expected-email:}") String email,
            @Value("${wealthos.gmail.sender-domains:axisbank.com,idfcfirstbank.com,icicibank.com,sbicard.com}") String domains,
            @Value("${wealthos.gmail.auto-record:true}") boolean autoRecord,
            @Value("${wealthos.gmail.scheduling-enabled:true}") boolean schedulingEnabled,
            @Value("${wealthos.gmail.credentials-file:${user.home}/.config/wealthos/google-oauth-client.json}") String credentials,
            @Value("${wealthos.gmail.key-file:${user.home}/.config/wealthos/gmail-token-key}") String key) {
        this.mapper=mapper;expectedEmail=email.trim().toLowerCase(Locale.ROOT);this.autoRecord=autoRecord;this.schedulingEnabled=schedulingEnabled;
        credentialsFile=Path.of(credentials);keyFile=Path.of(key);senderDomains=new LinkedHashSet<>();
        for(String domain:domains.split(",")) {
            String d=domain.trim().toLowerCase(Locale.ROOT);
            if(!d.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")) throw new IllegalArgumentException("Gmail sender domains must be domain names only");
            senderDomains.add(d);
        }
    }
    boolean configured() { try { credentials();return true; } catch(IllegalArgumentException ex) { return false; } }
    Credentials credentials() {
        if(!expectedEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw new IllegalArgumentException("Set the expected finance email in WealthOS Gmail settings");
        try {
            if(!Files.isRegularFile(credentialsFile) || Files.size(credentialsFile)>32768) throw new IllegalArgumentException("Add Google Desktop OAuth credentials to finish Gmail setup");
            var root=mapper.readTree(Files.readString(credentialsFile)).path("installed");
            String id=root.path("client_id").asText(),secret=root.path("client_secret").asText();
            if(!id.endsWith(".apps.googleusercontent.com") || secret.isBlank()) throw new IllegalArgumentException("Use the Google OAuth credentials JSON for a Desktop app");
            return new Credentials(id,secret);
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("The local Google OAuth credentials file could not be read"); }
    }
    String query() {
        String sources=String.join(" ",senderDomains.stream().map(d->"from:"+d).toList());
        return "-in:spam -in:trash -in:sent -in:drafts {"+sources+"} {debited credited spent paid purchase transaction payment refund}";
    }
}
