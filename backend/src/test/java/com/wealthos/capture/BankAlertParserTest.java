package com.wealthos.capture;

import com.wealthos.transaction.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BankAlertParserTest {
    private final BankAlertParser parser=new BankAlertParser();
    private final Instant received=Instant.parse("2026-10-29T11:00:00Z");
    private String debit(String reference) { return "Rs.250.50 debited from A/c XX1234 to SWIGGY on 29-10-2026 via UPI Ref No "+reference+". Avl Bal INR 9,000.00"; }
    @Test void extractsPaymentInsteadOfAvailableBalance() {
        var p=parser.parse(debit("123456789012"),received);
        assertEquals(new BigDecimal("250.50"),p.amount());
        assertEquals("••••1234",p.account());assertEquals("123456789012",p.reference());
        assertEquals("SWIGGY",p.description());assertEquals("Food & dining",p.category());
        assertEquals(LocalDate.of(2026,10,29),p.date());assertEquals(TransactionType.EXPENSE,p.type());
        assertTrue(p.autoEligible(),p.notes().toString());
    }
    @Test void masksFullAccountNumbers() {
        var p=parser.parse(debit("123456789012").replace("XX1234","12345678901234"),received);
        assertEquals("••••1234",p.account());
    }
    @Test void excludesOtpsAndUncompletedPayments() {
        for (String text:new String[]{"OTP 123456 for INR 500 purchase", "INR 250 debited but transaction failed", "Payment request for Rs 25", "Rs 500 will be debited on 29-10-2026"})
            assertTrue(parser.parse(text,received).ignored(),text);
    }
    @Test void creditsAndTwoDigitDatesAreSupported() {
        var p=parser.parse("INR 1,500.00 credited to account XX1234 from ACME on 29/10/26 Ref ABC123456789",received);
        assertEquals(TransactionType.INCOME,p.type());assertEquals(LocalDate.of(2026,10,29),p.date());
        assertEquals("ACME",p.description());assertTrue(p.autoEligible(),p.notes().toString());
    }
    @Test void missingOrAmbiguousFieldsRequireReview() {
        var p=parser.parse("Rs 500 spent at Cafe",received);
        assertFalse(p.autoEligible());assertFalse(p.ignored());assertTrue(p.notes().size()>=3);
        assertFalse(parser.parse(debit("123456789012")+" Cashback Rs 10",received).autoEligible());
        assertFalse(parser.parse("INR 200 debited and credited to A/c XX1234",received).autoEligible());
    }
    @Test void transferRefundAndForeignCurrencyNeverAutoRecord() {
        for (String text:new String[]{debit("123456789012")+" transferred",debit("123456789012")+" refund",debit("123456789012")+" USD 3"})
            assertFalse(parser.parse(text,received).autoEligible(),text);
    }
    @Test void usesIndiaDateForReceivedTimestampFallback() {
        var p=parser.parse("Rs 100 debited from A/c XX1234 to Cafe Ref 123456789012",Instant.parse("2026-10-28T20:00:00Z"));
        assertEquals(LocalDate.of(2026,10,29),p.date());assertFalse(p.autoEligible());
    }
    @Test void refusesRoundingPaymentAmounts() {
        var p=parser.parse(debit("123456789012").replace("250.50","250.501"),received);
        assertNull(p.amount());assertFalse(p.autoEligible());
    }
    @Test void differentBankReferencesCanRepresentRepeatedPurchases() {
        var one=new TransactionService.Input(LocalDate.of(2026,10,29),"Cafe",new BigDecimal("30"),TransactionType.EXPENSE,null,"••••1234","123456789012","UPI","SMS");
        var two=new TransactionService.Input(one.date(),one.description(),one.amount(),one.type(),null,one.account(),"123456789013","UPI","SMS");
        assertNotEquals(TransactionService.fingerprint(one),TransactionService.fingerprint(two));
        var repeated=new TransactionService.Input(one.date(),"CAFE PAYMENT",one.amount(),one.type(),null,one.account(),one.reference(),"UPI","SMS");
        assertEquals(TransactionService.fingerprint(one),TransactionService.fingerprint(repeated));
    }
}
