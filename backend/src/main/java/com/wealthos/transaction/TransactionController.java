package com.wealthos.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/transactions") @CrossOrigin(origins="http://localhost:3000")
public class TransactionController {
    private final TransactionService service;
    public TransactionController(TransactionService service) { this.service=service; }
    public record Request(LocalDate date, String description, BigDecimal amount, TransactionType type, UUID categoryId, String account) {}
    public record View(UUID id, LocalDate date, String description, BigDecimal amount, TransactionType type, UUID categoryId, String categoryName, String account,
                       String reference, String paymentMethod, String source) {
        static View from(Transaction t) { return new View(t.getId(),t.getDate(),t.getDescription(),t.getAmount(),t.getType(),t.getCategory()==null?null:t.getCategory().getId(),t.getCategory()==null?null:t.getCategory().getName(),t.getAccount(),t.getReference(),t.getPaymentMethod(),t.getSource()); }
    }
    private TransactionService.Input input(Request r) { return new TransactionService.Input(r.date(),r.description(),r.amount(),r.type(),r.categoryId(),r.account()); }
    @GetMapping public List<View> list() { return service.list().stream().map(View::from).toList(); }
    @GetMapping("/{id}") public View get(@PathVariable UUID id) { return View.from(service.get(id)); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) public View create(@RequestBody Request r) { return View.from(service.create(input(r))); }
    @PutMapping("/{id}") public View update(@PathVariable UUID id,@RequestBody Request r) { return View.from(service.update(id,input(r))); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable UUID id) { service.delete(id); }
}
