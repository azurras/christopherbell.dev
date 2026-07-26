package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import java.util.List;

/** Stable page of immutable audit entries with authoritative totals. */
public record AdminActivityPage(
    List<AdminActivity> items,
    int page,
    int size,
    long totalElements,
    int totalPages) {

  public AdminActivityPage {
    items = List.copyOf(items);
  }
}
