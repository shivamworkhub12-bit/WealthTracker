package com.wealthos.gmail;

import com.wealthos.capture.CaptureService;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class GmailSyncService {
    private final GmailSettings settings;
    private final GmailStore store;
    private final GmailGoogleClient google;
    private final GmailTokenCipher cipher;
    private final GmailEmailReader reader;
    private final CaptureService capture;
    private final AtomicBoolean syncing=new AtomicBoolean();
    private volatile String accessToken;
    private volatile Instant expires=Instant.EPOCH;
    private volatile UUID tokenGeneration;
    public record Status(boolean configured,boolean connected,String expectedEmail,String email,String state,String message,
                         Instant lastSynced,Instant retryAfter,Set<String> senderDomains,boolean autoRecord,long emailsChecked,String credentialsPath) {}
    public GmailSyncService(GmailSettings settings,GmailStore store,GmailGoogleClient google,GmailTokenCipher cipher,GmailEmailReader reader,CaptureService capture) {
        this.settings=settings;this.store=store;this.google=google;this.cipher=cipher;this.reader=reader;this.capture=capture;
    }
    public Status status() {
        var connection=store.connection();
        if(connection.isEmpty()) return new Status(settings.configured(),false,settings.expectedEmail,null,"NOT_CONNECTED",
                settings.configured()?"Ready for your Google authorization.":"Add your Google Desktop OAuth credentials to enable the Connect Gmail button.",null,null,settings.senderDomains,settings.autoRecord,0,settings.credentialsFile.toString());
        var c=connection.get();
        return new Status(settings.configured(),true,settings.expectedEmail,c.email(),syncing.get()?"SYNCING":c.state(),c.message(),c.lastSynced(),c.retryAfter(),settings.senderDomains,settings.autoRecord,store.checked(c.email()),settings.credentialsFile.toString());
    }
    public boolean trigger() {
        if(store.connection().isEmpty()) throw new IllegalArgumentException("Connect Gmail before syncing");
        if(!syncing.compareAndSet(false,true)) return false;
        Thread.startVirtualThread(()->{ try { sync(); } finally { syncing.set(false); } });
        return true;
    }
    @Scheduled(fixedDelayString="${wealthos.gmail.poll-ms:60000}",initialDelayString="${wealthos.gmail.initial-delay-ms:15000}")
    public void scheduled() {
        if(!settings.schedulingEnabled) return;
        var current=store.connection();
        if(current.isEmpty() || current.get().state().equals("RECONNECT_REQUIRED") || (current.get().retryAfter()!=null && current.get().retryAfter().isAfter(Instant.now()))) return;
        if(!syncing.compareAndSet(false,true)) return;
        try { sync(); } finally { syncing.set(false); }
    }
    /** One bounded page per round; a saved page token resumes the same fixed time window. */
    public void sync() {
        var maybe=store.connection();if(maybe.isEmpty()) return;
        var c=maybe.get();
        if(!c.email().equals(settings.expectedEmail)) { store.failed(c,"RECONNECT_REQUIRED","The configured finance account changed. Reconnect the intended account.");return; }
        try {
            String access=access(c);
            Instant after=c.windowAfter(),before=c.windowBefore();String page=c.pageToken();
            if(after==null || before==null) {
                before=Instant.now();
                after=c.lastSynced()==null?c.collectAfter():c.lastSynced().minus(Duration.ofHours(48));
                if(after.isBefore(c.collectAfter())) after=c.collectAfter();
                store.startWindow(c.generation(),after,before);page=null;
            }
            String query=settings.query()+" after:"+(after.getEpochSecond()-1)+" before:"+(before.getEpochSecond()+1);
            var batch=google.messages(access,query,page);
            for(var item:batch.path("messages")) {
                if(!store.same(c.generation())) return;
                String id=item.path("id").asText();
                if(store.seen(c.email(),id)) continue;
                com.fasterxml.jackson.databind.JsonNode metadata;
                try { metadata=google.message(access,id,false); }
                catch(GmailGoogleClient.GoogleFailure gone) { if(gone.status==404) {store.markSeen(c.email(),id);continue;} throw gone; }
                String sender=reader.sender(metadata);
                if(!reader.allowed(sender,settings.senderDomains)) { store.markSeen(c.email(),id);continue; }
                long millis=metadata.path("internalDate").asLong(-1);
                if(millis<0) throw new IllegalArgumentException("A bank email has no usable timestamp");
                Instant received=Instant.ofEpochMilli(millis);
                if(received.isBefore(c.collectAfter()) || received.isAfter(before)) continue;
                if(!store.same(c.generation())) return;
                var full=google.message(access,id,true);
                var text=reader.text(full);
                if(!store.same(c.generation())) return;
                String content=text.value().isBlank()?reader.header(full,"Subject"):text.value();
                if(content.isBlank()) content="No readable payment text was found in this bank email.";
                if(content.length()>6000) content=content.substring(0,6000);
                capture.ingestEmail(content,sender.length()>100?sender.substring(0,100):sender,c.email()+":"+id,received,
                        reader.authenticated(full,sender) && !text.truncated(),settings.autoRecord);
                store.markSeen(c.email(),id);
            }
            if(store.same(c.generation())) store.pageDone(c.generation(),batch.path("nextPageToken").asText(null),before);
        } catch(GmailGoogleClient.GoogleFailure ex) {
            accessToken=null;
            if(ex.invalidGrant || ex.status==401) store.failed(c,"RECONNECT_REQUIRED","Google authorization expired or was revoked. Reconnect Gmail to resume.");
            else if(ex.status==403) store.failed(c,"ERROR","Google denied the request. Check that Gmail API is enabled and read-only access was granted.");
            else if(ex.status==429) store.failed(c,"ERROR","Google rate-limited syncing. WealthOS will retry with backoff.");
            else if(ex.status==400 && c.pageToken()!=null) { store.startWindow(c.generation(),c.windowAfter(),c.windowBefore());store.failed(c,"ERROR","The Gmail page cursor expired. The saved window will restart safely."); }
            else store.failed(c,"ERROR","Gmail is temporarily unavailable. The saved sync window will retry.");
        } catch(IllegalStateException ex) {
            store.failed(c,"RECONNECT_REQUIRED","Gmail credentials could not be read. Reconnect Gmail using this Mac's OAuth setup.");
        } catch(RuntimeException ex) {
            // No email body, OAuth code, token, or Google error payload enters application logs.
            store.failed(c,"ERROR","Sync paused on an alert or local database error. No checkpoint was advanced; retry after checking the connection.");
        }
    }
    private synchronized String access(GmailStore.Connection c) {
        if(accessToken!=null && c.generation().equals(tokenGeneration) && expires.isAfter(Instant.now().plusSeconds(60))) return accessToken;
        var result=google.refresh(settings.credentials(),cipher.decrypt(c.encryptedToken()));
        String token=result.path("access_token").asText();if(token.isBlank()) throw new IllegalStateException("Missing access token");
        if(result.has("scope") && !Set.of(result.path("scope").asText().split("\\s+")).equals(Set.of(GmailSettings.SCOPE))) throw new IllegalStateException("Unexpected Gmail scope");
        if(result.hasNonNull("refresh_token")) store.rotate(c.generation(),cipher.encrypt(result.path("refresh_token").asText()));
        accessToken=token;tokenGeneration=c.generation();expires=Instant.now().plusSeconds(Math.max(60,result.path("expires_in").asLong(3600)));
        return token;
    }
    public boolean disconnect() {
        var connection=store.connection();store.disconnect();accessToken=null;tokenGeneration=null;expires=Instant.EPOCH;
        if(connection.isEmpty()) return true;
        try { google.revoke(cipher.decrypt(connection.get().encryptedToken()));return true; }
        catch(RuntimeException ex) { return false; }
    }
}
