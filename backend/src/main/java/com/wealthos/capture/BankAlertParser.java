package com.wealthos.capture;

import com.wealthos.transaction.TransactionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;

/** Conservative INR alert parser. Uncertain fields always require review. */
@Component
public class BankAlertParser {
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:INR|Rs\\.?|₹)\\s*([0-9]+(?:,[0-9]{2,3})*(?:\\.[0-9]+)?)(?![\\d.])");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)(?:a/c|acct?|account|card)(?:\\s+(?:no\\.?|number|ending(?:\\s+(?:in|with))?))?\\s*[:.-]?\\s*[Xx*•]*(\\d{4,20})");
    private static final Pattern REFERENCE = Pattern.compile("(?i)(?:UPI\\s+Ref(?:erence)?(?:\\s*(?:No\\.?|number))?|UTR|RRN|Ref(?:erence)?(?:\\s*(?:No\\.?|number))?)\\s*[:#.-]?\\s*([A-Z0-9]{6,40})\\b");
    private static final Pattern MERCHANT = Pattern.compile("(?i)\\b(?:paid\\s+to|at|to)\\s+([A-Za-z][A-Za-z0-9 &'._@/+-]{0,119}?)(?=\\s+(?:on|via|using|ref|upi|rrn|utr|avl|bal|available|txn|not\\s+you)\\b|[;\\n]|$)");
    private static final Pattern CREDIT_MERCHANT = Pattern.compile("(?i)\\bfrom\\s+([A-Za-z][A-Za-z0-9 &'._@/+-]{0,119}?)(?=\\s+(?:on|via|using|ref|upi|rrn|utr|avl|bal|available|txn)\\b|[;\\n]|$)");
    private static final Pattern DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|\\d{1,2}[- ]?[A-Za-z]{3}[- ]?\\d{2,4})\\b");
    private static final List<String> DATE_FORMATS = List.of("uuuu-MM-dd", "d-M-uuuu", "d/M/uuuu", "d-M-uu", "d/M/uu", "d-MMM-uuuu", "d-MMM-uu", "dMMMuuuu", "dMMMuu", "d MMM uuuu", "d MMM uu");

    public record Parsed(LocalDate date, String description, BigDecimal amount, TransactionType type,
                         String account, String reference, String paymentMethod, String category,
                         boolean ignored, boolean autoEligible, List<String> notes) {}

    public Parsed parse(String message, Instant receivedAt) {
        if (message==null || message.isBlank() || message.length()>6000) throw new IllegalArgumentException("Alert text must contain 1–6000 characters");
        String text=message.replace('\u00a0',' ').replaceAll("[\\t\\r]", " ");
        String lower=text.toLowerCase(Locale.ROOT);
        if (matches(lower,"\\b(?:otp|one[- ]time password|verification code|authentication code)\\s*(?:is\\s*)?[:.-]?\\s*\\d{4,8}\\b|\\b\\d{4,8}\\s+is\\s+(?:your\\s+)?otp\\b")
                || (matches(lower,"\\b(?:otp|one[- ]time password|verification code|authentication code)\\b") && !matches(lower,"\\b(?:debited|spent|paid|credited|received|withdrawn)\\b"))) return ignored("Verification message ignored; no message text was saved.");
        if (matches(lower,"\\b(?:failed|declined|unsuccessful|pending|collect request|payment request|due date|payment due|will be debited|scheduled)\\b")) return ignored("Not a confirmed completed payment.");
        boolean debit=matches(lower,"\\b(?:debited|spent|paid|purchased|purchase|withdrawn|charged)\\b|\\b(?:transaction type|type|direction)\\s*[:=-]?\\s*debit\\b|(?m)^\\s*debit\\s*$|(?s)\\bcard\\b.{0,150}\\bused\\b");
        boolean credit=matches(lower,"\\b(?:credited|received|refunded|reversed)\\b|\\b(?:transaction type|type|direction)\\s*[:=-]?\\s*credit\\b|(?m)^\\s*credit\\s*$");
        if (!debit && !credit) return ignored("No completed debit or credit was found.");

        List<String> notes=new ArrayList<>();
        TransactionType type=debit==credit?null:debit?TransactionType.EXPENSE:TransactionType.INCOME;
        if (type==null) notes.add("Both debit and credit appear. Choose the transaction type.");
        if (matches(lower,"\\b(?:usd|eur|gbp|aed|dollars?)\\b") || text.contains("$")) notes.add("Only INR is supported. Check the currency.");
        List<BigDecimal> amounts=new ArrayList<>();
        Matcher amountMatcher=AMOUNT.matcher(text);
        while (amountMatcher.find()) {
            String prefix=lower.substring(Math.max(0,amountMatcher.start()-30),amountMatcher.start());
            if (prefix.matches("(?s).*(?:bal(?:ance)?|avl(?:\\s+bal)?|available(?:\\s+balance)?|limit)\\s*[:.-]?\\s*$")) continue;
            try {
                BigDecimal value=new BigDecimal(amountMatcher.group(1).replace(",", "")).setScale(2,RoundingMode.UNNECESSARY);
                if (value.signum()>0 && value.compareTo(new BigDecimal("999999999999.99"))<=0) amounts.add(value);
            } catch (ArithmeticException ex) { notes.add("An amount has unsupported precision."); }
        }
        BigDecimal amount=amounts.isEmpty()?null:amounts.getFirst();
        if (amount==null) notes.add("A positive INR transaction amount was not found.");
        if (amounts.size()>1) notes.add("Multiple payment amounts appear. Check the amount.");

        String account=find(ACCOUNT,text);
        if (account!=null) account="••••"+account.substring(account.length()-4);
        else notes.add("The account or card ending was not found.");
        String reference=find(REFERENCE,text);
        if (reference==null) notes.add("The bank reference is missing; automatic recording is disabled for this alert.");
        else reference=reference.toUpperCase(Locale.ROOT);
        String merchant=find(MERCHANT,text);
        if (merchant!=null) {
            merchant=merchant.trim().replaceAll("[. ]+$", "");
            if (merchant.matches("(?i)(?:your\\s+)?(?:a/c|account|card).*")) merchant=null;
        }
        if (merchant==null && credit) merchant=find(CREDIT_MERCHANT,text);
        if (merchant==null) merchant=find(Pattern.compile("(?i)(?:merchant(?: name)?|beneficiary(?: name)?|payee|recipient)\\s*[:=-]?\\s*([^\\n]{1,120})"),text);
        if (merchant==null) notes.add("The merchant or sender was not found.");
        LocalDate date=null; Matcher dateMatcher=DATE.matcher(text);
        if (dateMatcher.find()) date=parseDate(dateMatcher.group(1));
        LocalDate receivedDate=receivedAt.atZone(ZONE).toLocalDate();
        if (date==null) { date=receivedDate; notes.add("Date inferred from the SMS timestamp. Check it before recording."); }
        if (date.isAfter(receivedDate.plusDays(1))) notes.add("Transaction date is later than the SMS timestamp.");
        if (matches(lower,"\\b(?:refund|refunded|reversed|reversal|transfer|transferred|neft|imps|rtgs)\\b")) notes.add("Check whether this is a refund or an account transfer before counting it as income or spending.");
        String method=matches(lower,"\\bupi\\b")?"UPI":matches(lower,"\\batm\\b")?"ATM":matches(lower,"\\bcard\\b")?"CARD":"BANK";
        String category=suggestCategory(merchant);
        return new Parsed(date,merchant,amount,type,account,reference,method,category,false,notes.isEmpty(),List.copyOf(notes));
    }

    private Parsed ignored(String note) { return new Parsed(null,null,null,null,null,null,null,null,true,false,List.of(note)); }
    private static boolean matches(String text,String expression) { return Pattern.compile(expression,Pattern.CASE_INSENSITIVE).matcher(text).find(); }
    private static String find(Pattern pattern,String text) { Matcher m=pattern.matcher(text); return m.find()?m.group(1):null; }
    private LocalDate parseDate(String value) {
        for (String format:DATE_FORMATS) {
            try { return LocalDate.parse(value,new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(format).toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)); }
            catch (DateTimeParseException ignored) { }
        }
        return null;
    }
    private String suggestCategory(String merchant) {
        if (merchant==null) return null;
        String m=merchant.toLowerCase(Locale.ROOT);
        if (matches(m,"swiggy|zomato|restaurant|cafe|grocery|groceries|bigbasket|blinkit|zepto|dmart")) return "Food & dining";
        if (matches(m,"uber|ola|rapido|metro|irctc|petrol|fuel")) return "Transport";
        if (matches(m,"electric|airtel|jio|broadband|water bill")) return "Utilities";
        if (matches(m,"apollo|pharmacy|hospital|clinic")) return "Health";
        if (matches(m,"amazon|flipkart|myntra")) return "Shopping";
        return null;
    }
}
