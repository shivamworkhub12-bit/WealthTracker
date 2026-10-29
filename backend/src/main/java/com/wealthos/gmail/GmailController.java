package com.wealthos.gmail;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/gmail") @CrossOrigin(origins="http://localhost:3000")
public class GmailController {
    private final GmailAuthorization auth;
    private final GmailSyncService sync;
    public GmailController(GmailAuthorization auth,GmailSyncService sync) { this.auth=auth;this.sync=sync; }
    public record Connect(int lookbackDays) {}
    @GetMapping("/status") public GmailSyncService.Status status() { return sync.status(); }
    @PostMapping("/connect") public Map<String,String> connect(@RequestBody Connect body) { return Map.of("url",auth.start(body.lookbackDays())); }
    @GetMapping("/authorize") public ResponseEntity<Void> authorize(@RequestParam String ticket) {
        try {
            var launch=auth.launch(ticket);
            var cookie=ResponseCookie.from("wealthos_gmail_oauth",launch.cookie()).httpOnly(true).sameSite("Lax").path("/api/gmail/oauth").maxAge(Duration.ofMinutes(10)).build();
            return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE,cookie.toString()).header("Referrer-Policy","no-referrer").cacheControl(CacheControl.noStore()).location(URI.create(launch.url())).build();
        } catch(GmailAuthorization.ConsentFailure ex) { return redirect(ex.reason); }
    }
    @GetMapping("/oauth/callback") public ResponseEntity<Void> callback(@RequestParam(required=false) String code,@RequestParam(required=false) String state,
                @RequestParam(required=false) String error,@CookieValue(value="wealthos_gmail_oauth",required=false) String cookie) {
        try { auth.finish(state,code,cookie,error);return redirect("connected"); }
        catch(GmailAuthorization.ConsentFailure ex) { return redirect(ex.reason); }
        catch(RuntimeException ex) { return redirect("failed"); }
    }
    private ResponseEntity<Void> redirect(String result) {
        var clear=ResponseCookie.from("wealthos_gmail_oauth","").httpOnly(true).sameSite("Lax").path("/api/gmail/oauth").maxAge(0).build();
        return ResponseEntity.status(302).header(HttpHeaders.SET_COOKIE,clear.toString()).header("Referrer-Policy","no-referrer").cacheControl(CacheControl.noStore()).location(URI.create("http://localhost:3000/?gmail="+result)).build();
    }
    @PostMapping("/sync") public ResponseEntity<Map<String,Object>> sync() { return ResponseEntity.accepted().body(Map.of("queued",sync.trigger())); }
    @PostMapping("/disconnect") public Map<String,Object> disconnect() { auth.cancelPending();boolean revoked=sync.disconnect();return Map.of("disconnected",true,"googleRevoked",revoked); }
}
