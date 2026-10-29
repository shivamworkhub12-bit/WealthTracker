package com.wealthos.capture;

import com.wealthos.transaction.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Entity @Table(name="captured_alerts")
class CapturedAlert {
    @Id UUID id=UUID.randomUUID();
    @Column(name="event_key",nullable=false,unique=true,length=64) String eventKey;
    @Column(nullable=false,length=100) String sender;
    @Column(nullable=false,length=20) String source;
    @Column(name="received_at",nullable=false) Instant receivedAt;
    @Column(nullable=false,length=20) String status;
    LocalDate date;
    @Column(length=200) String description;
    @Column(precision=14,scale=2) BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(length=10) TransactionType type;
    @Column(length=80) String account;
    @Column(length=80) String reference;
    @Column(name="payment_method",length=20) String paymentMethod;
    @Column(name="suggested_category",length=80) String suggestedCategory;
    @Column(nullable=false,columnDefinition="text") String notes="";
    @Column(name="transaction_id") UUID transactionId;
    @Column(name="created_at",nullable=false) Instant createdAt=Instant.now();
    protected CapturedAlert() {}
    CapturedAlert(String key,String sender,String source,Instant receivedAt,BankAlertParser.Parsed p) {
        this.eventKey=key;this.sender=sender;this.source=source;this.receivedAt=receivedAt;
        status=p.ignored()?"IGNORED":"REVIEW";date=p.date();description=p.description();amount=p.amount();type=p.type();
        account=p.account();reference=p.reference();paymentMethod=p.paymentMethod();suggestedCategory=p.category();notes=String.join("\n",p.notes());
    }
}
