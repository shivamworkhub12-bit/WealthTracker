package com.wealthos.transaction;

import com.wealthos.category.Category;
import com.wealthos.category.CategoryRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.Locale;
import java.math.RoundingMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    public TransactionService(TransactionRepository transactions, CategoryRepository categories) { this.transactions=transactions; this.categories=categories; }
    public record Input(LocalDate date, String description, java.math.BigDecimal amount, TransactionType type, UUID categoryId, String account,
                        String reference, String paymentMethod, String source) {
        public Input(LocalDate date, String description, java.math.BigDecimal amount, TransactionType type, UUID categoryId, String account) {
            this(date,description,amount,type,categoryId,account,null,null,"MANUAL");
        }
    }
    @Transactional(readOnly = true) public List<Transaction> list() { return transactions.findAllByDateBetweenOrderByDateDesc(LocalDate.of(1900,1,1), LocalDate.of(9999,12,31)); }
    @Transactional(readOnly = true) public Transaction get(UUID id) { return transactions.findById(id).orElseThrow(() -> new NoSuchElementException("Transaction not found")); }
    @Transactional public Transaction create(Input in) {
        validate(in);
        String fingerprint = fingerprint(in);
        if (transactions.existsByFingerprint(fingerprint)) throw new IllegalArgumentException("Duplicate transaction");
        Transaction t = new Transaction(in.date(), in.description().trim(), in.amount().setScale(2), in.type(), category(in.categoryId()), in.account(), fingerprint);
        t.setCaptureDetails(clean(in.reference()), clean(in.paymentMethod()), in.source()==null?"MANUAL":in.source());
        return transactions.saveAndFlush(t);
    }
    @Transactional public Transaction update(UUID id, Input in) {
        validate(in); Transaction t=get(id);
        Input withMetadata = new Input(in.date(),in.description(),in.amount(),in.type(),in.categoryId(),in.account(),
                t.getReference(),t.getPaymentMethod(),t.getSource());
        String fp=fingerprint(withMetadata);
        transactions.findByFingerprint(fp).filter(other -> !other.getId().equals(id)).ifPresent(other -> { throw new IllegalArgumentException("Duplicate transaction"); });
        t.update(in.date(), in.description().trim(), in.amount(), in.type(), category(in.categoryId()), in.account(), fp); return transactions.save(t);
    }
    @Transactional public void delete(UUID id) { transactions.delete(get(id)); }
    private Category category(UUID id) { return id == null ? null : categories.findById(id).orElseThrow(() -> new IllegalArgumentException("Category not found")); }
    private void validate(Input in) {
        if (in.date()==null || in.amount()==null || in.type()==null || in.description()==null || in.description().isBlank()) throw new IllegalArgumentException("Date, description, amount, and type are required");
        if (in.amount().signum()<=0) throw new IllegalArgumentException("Amount must be greater than zero");
        try { in.amount().setScale(2, RoundingMode.UNNECESSARY); } catch (ArithmeticException ex) { throw new IllegalArgumentException("Amount may have at most two decimal places"); }
        if (in.amount().compareTo(new java.math.BigDecimal("999999999999.99"))>0) throw new IllegalArgumentException("Amount is too large");
        if (in.description().length()>200) throw new IllegalArgumentException("Description must be 200 characters or fewer");
        if (in.account()!=null && in.account().length()>80) throw new IllegalArgumentException("Account must be 80 characters or fewer");
        if (in.reference()!=null && in.reference().length()>80) throw new IllegalArgumentException("Reference must be 80 characters or fewer");
    }
    public static String fingerprint(Input in) {
        String account=in.account()==null?"":in.account().trim().toLowerCase(Locale.ROOT);
        String normalized=clean(in.reference())!=null
                ? String.join("|", "reference", account, in.type().name(), in.reference().trim().toUpperCase(Locale.ROOT))
                : String.join("|", in.date().toString(), in.description().trim().toLowerCase(Locale.ROOT), in.amount().stripTrailingZeros().toPlainString(), in.type().name(), account);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String clean(String value) { return value==null || value.isBlank()?null:value.trim(); }
}
