package com.wealthos.capture;

import com.wealthos.category.CategoryRepository;
import com.wealthos.transaction.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaptureService {
    private final BankAlertParser parser;
    private final CapturedAlertRepository alerts;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final CategoryRepository categories;
    private final CaptureSettings settings;
    private final JdbcTemplate jdbc;

    CaptureService(BankAlertParser parser,CapturedAlertRepository alerts,TransactionRepository transactions,
                   TransactionService transactionService,CategoryRepository categories,CaptureSettings settings,JdbcTemplate jdbc) {
        this.parser=parser;this.alerts=alerts;this.transactions=transactions;this.transactionService=transactionService;
        this.categories=categories;this.settings=settings;this.jdbc=jdbc;
    }
    public record View(UUID id,String sender,String source,Instant receivedAt,String status,LocalDate date,String description,
                       java.math.BigDecimal amount,TransactionType type,String account,String reference,String paymentMethod,
                       String suggestedCategory,List<String> notes,UUID transactionId) {}
    public record Status(boolean configured,boolean autoRecord,String collectorState,Instant lastSeen,String message,
                         Set<String> allowedSenders,long review,long recorded,long duplicates,long ignored) {}
    private View view(CapturedAlert a) { return new View(a.id,a.sender,a.source,a.receivedAt,a.status,a.date,a.description,a.amount,a.type,a.account,a.reference,a.paymentMethod,a.suggestedCategory,a.notes.isBlank()?List.of():List.of(a.notes.split("\n")),a.transactionId); }
    @Transactional(readOnly=true) public List<View> list() { return alerts.findTop100ByOrderByCreatedAtDesc().stream().map(this::view).toList(); }

    @Transactional public View ingest(String message,String sender,String sourceId,Instant receivedAt,boolean collector) {
        // Serialize capture writes across requests; the event and transaction commit together.
        lock();
        String source=collector?"SMS":"PASTED_SMS";
        String key=hash(source+"|"+(sourceId==null || sourceId.isBlank()?hash(message.trim()):sourceId));
        var existing=alerts.findByEventKey(key);
        if (existing.isPresent()) return view(existing.get());
        Instant timestamp=receivedAt==null?Instant.now():receivedAt;
        if (timestamp.isAfter(Instant.now().plusSeconds(300))) throw new IllegalArgumentException("SMS timestamp cannot be in the future");
        var parsed=parser.parse(message,timestamp);
        var alert=new CapturedAlert(key,sender==null || sender.isBlank()?"Pasted alert":sender.trim(),source,timestamp,parsed);
        alerts.save(alert);
        if (!parsed.ignored()) {
            var exact=parsed.amount()!=null && parsed.type()!=null && parsed.description()!=null
                    ? transactions.findByFingerprint(TransactionService.fingerprint(input(alert,null))):Optional.<Transaction>empty();
            if (exact.isPresent() && parsed.reference()!=null) {
                Transaction t=exact.get();
                if (t.getAmount().compareTo(parsed.amount())==0 && t.getDate().equals(parsed.date())) {
                    alert.status="DUPLICATE";alert.transactionId=t.getId();alert.notes="This bank reference was already recorded.";
                } else alert.notes=append(alert.notes,"This bank reference matches a record with different details. Check it before saving.");
            } else if (potentialDuplicate(alert)) {
                alert.notes=append(alert.notes,"A similar transaction already exists. Check for a manual entry or another alert.");
            } else if (collector && settings.autoRecord && parsed.autoEligible()) {
                record(alert,input(alert,null));
            }
        }
        return view(alerts.saveAndFlush(alert));
    }

    @Transactional public View accept(UUID id,TransactionService.Input input,boolean allowSimilar) {
        lock();CapturedAlert alert=alerts.findById(id).orElseThrow(()->new NoSuchElementException("Alert not found"));
        if (alert.status.equals("RECORDED") || alert.status.equals("DUPLICATE")) return view(alert);
        if (!alert.status.equals("REVIEW")) throw new IllegalArgumentException("Only alerts awaiting review can be recorded");
        var full=new TransactionService.Input(input.date(),input.description(),input.amount(),input.type(),input.categoryId(),input.account(),input.reference(),input.paymentMethod(),"SMS");
        if (full.date()==null || full.amount()==null || full.type()==null || full.description()==null || full.description().isBlank()) throw new IllegalArgumentException("Check the date, amount, type, and description");
        String fp=TransactionService.fingerprint(full);
        var existing=transactions.findByFingerprint(fp);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(full.amount())!=0 || !existing.get().getDate().equals(full.date()))
                throw new IllegalArgumentException("This bank reference already has different recorded details. Correct the reference or inspect the original transaction.");
            alert.status="DUPLICATE";alert.transactionId=existing.get().getId();alert.notes=append(alert.notes,"This transaction was already recorded.");
        } else {
            // Use the reviewed values for the similarity check.
            alert.date=full.date();alert.amount=full.amount();alert.type=full.type();alert.description=full.description();alert.account=full.account();
            if (potentialDuplicate(alert) && !allowSimilar) throw new IllegalArgumentException("A similar transaction exists. Confirm that this is a separate payment.");
            record(alert,full);
        }
        return view(alerts.saveAndFlush(alert));
    }
    @Transactional public View ignore(UUID id) {
        lock();CapturedAlert alert=alerts.findById(id).orElseThrow(()->new NoSuchElementException("Alert not found"));
        if (!alert.status.equals("REVIEW")) throw new IllegalArgumentException("Only an alert awaiting review can be dismissed");
        alert.status="IGNORED";alert.notes=append(alert.notes,"Dismissed during review.");return view(alerts.save(alert));
    }
    @Transactional(readOnly=true) public Status status() {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT state,last_seen,message FROM capture_collector WHERE id=1");
        String state="NOT_CONNECTED",message="Approve your bank senders and Messages access to enable local capture.";Instant last=null;
        if (!rows.isEmpty()) {
            var row=rows.getFirst();state=(String)row.get("state");last=((java.sql.Timestamp)row.get("last_seen")).toInstant();message=(String)row.get("message");
            if (state.equals("RUNNING") && last.isBefore(Instant.now().minusSeconds(120))) { state="OFFLINE";message="The collector has stopped or this Mac was asleep. Start it to catch up."; }
        }
        return new Status(settings.configured(),settings.autoRecord,state,last,message,settings.senders,alerts.countByStatus("REVIEW"),alerts.countByStatus("RECORDED"),alerts.countByStatus("DUPLICATE"),alerts.countByStatus("IGNORED"));
    }
    public void heartbeat(String state,String message) {
        if (!Set.of("RUNNING","PERMISSION_REQUIRED","ERROR","PAUSED").contains(state)) throw new IllegalArgumentException("Invalid collector state");
        jdbc.update("INSERT INTO capture_collector(id,state,last_seen,message) VALUES(1,?,now(),?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,last_seen=excluded.last_seen,message=excluded.message",state,message);
    }
    private void record(CapturedAlert alert,TransactionService.Input input) {
        Transaction saved=transactionService.create(input);alert.status="RECORDED";alert.transactionId=saved.getId();
        alert.reference=saved.getReference();alert.paymentMethod=saved.getPaymentMethod();
    }
    private TransactionService.Input input(CapturedAlert a,UUID categoryId) {
        if (categoryId==null && a.suggestedCategory!=null) categoryId=categories.findAll().stream()
                .filter(c->c.getName().equals(a.suggestedCategory) && a.type!=null && c.getKind().equals(a.type.name())).map(c->c.getId()).findFirst().orElse(null);
        return new TransactionService.Input(a.date,a.description,a.amount,a.type,categoryId,a.account,a.reference,a.paymentMethod,"SMS");
    }
    private boolean potentialDuplicate(CapturedAlert a) {
        if (a.date==null || a.amount==null || a.type==null) return false;
        return transactions.findAllByDateBetweenOrderByDateDesc(a.date,a.date).stream().anyMatch(t->
                t.getType()==a.type && t.getAmount().compareTo(a.amount)==0
                && (Objects.equals(lower(t.getDescription()),lower(a.description)) || (a.account!=null && a.account.equals(t.getAccount())))
                && !(a.reference!=null && t.getReference()!=null && !a.reference.equals(t.getReference())));
    }
    private void lock() { jdbc.execute("SELECT pg_advisory_xact_lock(87021341001)"); }
    private static String lower(String value) { return value==null?null:value.trim().toLowerCase(Locale.ROOT); }
    private static String append(String current,String note) { return current.isBlank()?note:current+"\n"+note; }
    private static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
