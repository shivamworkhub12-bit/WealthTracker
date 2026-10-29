package com.wealthos.capture;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
class CaptureSettings {
    private final Path tokenFile;
    final Set<String> senders;
    final boolean autoRecord;
    CaptureSettings(@Value("${wealthos.capture.token-file:${user.home}/.config/wealthos/capture-token}") String tokenFile,
                    @Value("${wealthos.capture.allowed-senders:}") String senders,
                    @Value("${wealthos.capture.auto-record:false}") boolean autoRecord) {
        this.tokenFile=Path.of(tokenFile);
        this.senders=new LinkedHashSet<>();
        Arrays.stream(senders.split(",")).map(String::trim).filter(s->!s.isEmpty()).map(CaptureSettings::normalizeSender).forEach(this.senders::add);
        this.autoRecord=autoRecord;
    }
    boolean configured() { return !senders.isEmpty() && Files.isRegularFile(tokenFile); }
    void authenticate(String authorization) {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SMS capture is not connected yet");
        try {
            String token=Files.readString(tokenFile).trim();
            String supplied=authorization!=null && authorization.startsWith("Bearer ")?authorization.substring(7):"";
            if (token.length()<32 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Collector token is invalid");
        } catch (java.io.IOException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Collector token is unavailable"); }
    }
    void requireSender(String sender) {
        if (sender==null || !senders.contains(normalizeSender(sender))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"This SMS sender is not approved");
    }
    private static String normalizeSender(String sender) { return sender.trim().toUpperCase(Locale.ROOT).replaceFirst("^[A-Z]{2}-", ""); }
}
