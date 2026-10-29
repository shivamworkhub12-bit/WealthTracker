package com.wealthos.dashboard;

import com.wealthos.transaction.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/dashboard") @CrossOrigin(origins="http://localhost:3000")
public class DashboardController {
    private final TransactionRepository transactions;
    public DashboardController(TransactionRepository transactions) { this.transactions=transactions; }
    public record CategoryTotal(String name, String color, BigDecimal total) {}
    public record Summary(String month, BigDecimal income, BigDecimal expenses, BigDecimal balance, List<CategoryTotal> byCategory, List<TransactionControllerView> recent) {}
    public record TransactionControllerView(UUID id,String date,String description,BigDecimal amount,String type,String categoryName,String account) {}
    @GetMapping public Summary summary(@RequestParam(required=false) String month) {
        YearMonth ym;
        try { ym=month==null?YearMonth.now():YearMonth.parse(month); } catch (Exception ex) { throw new IllegalArgumentException("Month must use YYYY-MM format"); }
        List<Transaction> items=transactions.findAllByDateBetweenOrderByDateDesc(ym.atDay(1),ym.atEndOfMonth());
        BigDecimal income=sum(items,TransactionType.INCOME), expenses=sum(items,TransactionType.EXPENSE);
        Map<String,BigDecimal> totals=new LinkedHashMap<>(); Map<String,String> colors=new HashMap<>();
        items.stream().filter(t->t.getType()==TransactionType.EXPENSE).forEach(t->{ String n=t.getCategory()==null?"Uncategorized":t.getCategory().getName(); totals.merge(n,t.getAmount(),BigDecimal::add); colors.put(n,t.getCategory()==null?"#94A3B8":t.getCategory().getColor()); });
        List<CategoryTotal> byCategory=totals.entrySet().stream().map(e->new CategoryTotal(e.getKey(),colors.get(e.getKey()),e.getValue())).toList();
        List<TransactionControllerView> recent=items.stream().limit(8).map(t->new TransactionControllerView(t.getId(),t.getDate().toString(),t.getDescription(),t.getAmount(),t.getType().name(),t.getCategory()==null?null:t.getCategory().getName(),t.getAccount())).toList();
        return new Summary(ym.toString(),income,expenses,income.subtract(expenses),byCategory,recent);
    }
    private BigDecimal sum(List<Transaction> items,TransactionType type) { return items.stream().filter(t->t.getType()==type).map(Transaction::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add); }
}
