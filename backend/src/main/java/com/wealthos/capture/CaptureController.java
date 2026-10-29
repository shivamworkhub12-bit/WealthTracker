package com.wealthos.capture;

import com.wealthos.transaction.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/capture") @CrossOrigin(origins="http://localhost:3000")
class CaptureController {
    private final CaptureService service;
    private final BankAlertParser parser;
    private final CaptureSettings settings;
    CaptureController(CaptureService service,BankAlertParser parser,CaptureSettings settings) { this.service=service;this.parser=parser;this.settings=settings; }
    record AlertRequest(@NotBlank @Size(max=6000) String text,@Size(max=100) String sender,@Size(max=200) String sourceId,Instant receivedAt) {}
    record AcceptRequest(@NotNull TransactionService.Input transaction,boolean separatePayment) {}
    record Heartbeat(@NotBlank String state,@NotBlank @Size(max=250) String message) {}
    @GetMapping("/status") CaptureService.Status status() { return service.status(); }
    @GetMapping("/alerts") List<CaptureService.View> alerts() { return service.list(); }
    @PostMapping("/preview") BankAlertParser.Parsed preview(@Valid @RequestBody AlertRequest r) { return parser.parse(r.text(),r.receivedAt()==null?Instant.now():r.receivedAt()); }
    @PostMapping("/alerts") CaptureService.View paste(@Valid @RequestBody AlertRequest r) { return service.ingest(r.text(),r.sender(),null,r.receivedAt(),false); }
    @PostMapping("/alerts/{id}/accept") CaptureService.View accept(@PathVariable UUID id,@Valid @RequestBody AcceptRequest r) { return service.accept(id,r.transaction(),r.separatePayment()); }
    @PostMapping("/alerts/{id}/ignore") CaptureService.View ignore(@PathVariable UUID id) { return service.ignore(id); }
    @PostMapping("/incoming") CaptureService.View incoming(@RequestHeader(value="Authorization",required=false) String auth,@Valid @RequestBody AlertRequest r) {
        settings.authenticate(auth);settings.requireSender(r.sender());
        if (r.sourceId()==null || r.sourceId().isBlank() || r.receivedAt()==null) throw new IllegalArgumentException("Collector alerts require a source ID and SMS timestamp");
        return service.ingest(r.text(),r.sender(),r.sourceId(),r.receivedAt(),true);
    }
    @PostMapping("/heartbeat") void heartbeat(@RequestHeader(value="Authorization",required=false) String auth,@Valid @RequestBody Heartbeat r) {
        settings.authenticate(auth);service.heartbeat(r.state(),r.message());
    }
}
