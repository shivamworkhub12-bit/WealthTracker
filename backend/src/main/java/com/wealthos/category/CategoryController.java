package com.wealthos.category;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
@CrossOrigin(origins = "http://localhost:3000")
public class CategoryController {
    private final CategoryRepository categories;
    public CategoryController(CategoryRepository categories) { this.categories = categories; }
    public record CategoryRequest(@NotBlank String name, @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color,
                                  @Pattern(regexp = "INCOME|EXPENSE") String kind) {}
    public record CategoryView(UUID id, String name, String color, String kind) {
        static CategoryView from(Category c) { return new CategoryView(c.getId(), c.getName(), c.getColor(), c.getKind()); }
    }
    @GetMapping public List<CategoryView> list() { return categories.findAll().stream().map(CategoryView::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CategoryView create(@Valid @RequestBody CategoryRequest body) {
        if (categories.existsByNameIgnoreCase(body.name())) throw new IllegalArgumentException("Category already exists");
        return CategoryView.from(categories.save(new Category(body.name().trim(), body.color() == null ? "#64748B" : body.color(),
                body.kind() == null ? "EXPENSE" : body.kind())));
    }
    @PutMapping("/{id}")
    public CategoryView update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest body) {
        Category c = categories.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("Category not found"));
        c.update(body.name().trim(), body.color() == null ? c.getColor() : body.color(), body.kind() == null ? c.getKind() : body.kind());
        return CategoryView.from(categories.save(c));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        if (!categories.existsById(id)) throw new java.util.NoSuchElementException("Category not found");
        categories.deleteById(id);
    }
}
