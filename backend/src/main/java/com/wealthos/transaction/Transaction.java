package com.wealthos.transaction;

import com.wealthos.category.Category;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false) private LocalDate date;
    @Column(nullable = false, length = 200) private String description;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private TransactionType type;
    @ManyToOne(fetch = FetchType.EAGER) @JoinColumn(name = "category_id") private Category category;
    @Column(length = 80) private String account;
    @Column(nullable = false, unique = true, length = 64) private String fingerprint;
    @Column(length = 80) private String reference;
    @Column(name = "payment_method", length = 20) private String paymentMethod;
    @Column(nullable = false, length = 20) private String source = "MANUAL";

    protected Transaction() {}
    public Transaction(LocalDate date, String description, BigDecimal amount, TransactionType type, Category category, String account, String fingerprint) {
        this.date=date; this.description=description; this.amount=amount; this.type=type; this.category=category; this.account=account; this.fingerprint=fingerprint;
    }
    public UUID getId() { return id; }
    public LocalDate getDate() { return date; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public TransactionType getType() { return type; }
    public Category getCategory() { return category; }
    public String getAccount() { return account; }
    public String getFingerprint() { return fingerprint; }
    public String getReference() { return reference; }
    public String getPaymentMethod() { return paymentMethod; }
    public String getSource() { return source; }
    public void setCaptureDetails(String reference, String paymentMethod, String source) {
        this.reference=reference; this.paymentMethod=paymentMethod; this.source=source;
    }
    public void update(LocalDate date, String description, BigDecimal amount, TransactionType type, Category category, String account, String fingerprint) {
        this.date=date; this.description=description; this.amount=amount; this.type=type; this.category=category; this.account=account; this.fingerprint=fingerprint;
    }
}
