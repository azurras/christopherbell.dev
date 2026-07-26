package dev.christopherbell.account;

import dev.christopherbell.account.model.dto.AccountDetail;
import java.util.List;

/** A bounded page of account details for back-office administration. */
public record AdminAccountPage(
    List<AccountDetail> items,
    int page,
    int size,
    long totalElements,
    int totalPages,
    String sort,
    String direction
) {
  public AdminAccountPage {
    items = List.copyOf(items);
  }
}
