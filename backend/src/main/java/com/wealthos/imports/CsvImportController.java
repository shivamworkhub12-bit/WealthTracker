package com.wealthos.imports;

import com.wealthos.category.Category;
import com.wealthos.category.CategoryRepository;
import com.wealthos.transaction.*;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/imports") @CrossOrigin(origins="http://localhost:3000")
public class CsvImportController {
    private final TransactionService transactions;
    private final CategoryRepository categories;
    public CsvImportController(TransactionService transactions, CategoryRepository categories) { this.transactions=transactions; this.categories=categories; }
    public record ImportResult(int imported, int duplicates, List<String> errors) {}

    @PostMapping(value="/csv", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty CSV file");
        int imported=0, duplicates=0, row=1; List<String> errors=new ArrayList<>();
        try (var reader=new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(reader)) {
            Set<String> required=Set.of("date","description","amount","type","category","account");
            if (!parser.getHeaderMap().keySet().containsAll(required)) throw new IllegalArgumentException("CSV headers must include: date, description, amount, type, category, account");
            Map<String, UUID> categoryIds = new HashMap<>();
            categories.findAll().forEach(c -> categoryIds.put(c.getName().toLowerCase(Locale.ROOT), c.getId()));
            for (CSVRecord record : parser) {
                row++;
                try {
                    String description=record.get("description").trim();
                    LocalDate date=LocalDate.parse(record.get("date").trim());
                    BigDecimal amount=new BigDecimal(record.get("amount").trim().replace(",", ""));
                    TransactionType type=TransactionType.valueOf(record.get("type").trim().toUpperCase(Locale.ROOT));
                    String categoryName=record.get("category").trim();
                    UUID categoryId=categoryName.isBlank()?null:categoryIds.get(categoryName.toLowerCase(Locale.ROOT));
                    var input=new TransactionService.Input(date,description,amount,type,categoryId,record.get("account").trim());
                    try { transactions.create(input); imported++; } catch (IllegalArgumentException duplicate) {
                        if ("Duplicate transaction".equals(duplicate.getMessage())) duplicates++; else throw duplicate;
                    }
                } catch (RuntimeException ex) {
                    errors.add("Row " + row + ": " + ex.getMessage());
                }
            }
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("Could not read CSV: " + ex.getMessage()); }
        return new ImportResult(imported,duplicates,errors);
    }
}
