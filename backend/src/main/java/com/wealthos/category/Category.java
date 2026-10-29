package com.wealthos.category;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "categories")
public class Category {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false, unique = true, length = 80)
    private String name;
    @Column(nullable = false, length = 20)
    private String color;
    @Column(nullable = false, length = 10)
    private String kind;

    protected Category() {}
    public Category(String name, String color, String kind) { this.name = name; this.color = color; this.kind = kind; }
    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getColor() { return color; }
    public String getKind() { return kind; }
    public void update(String name, String color, String kind) { this.name = name; this.color = color; this.kind = kind; }
}
