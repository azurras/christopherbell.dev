package dev.christopherbell.account;

import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import dev.christopherbell.libs.api.exception.InvalidRequestException;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Sort;

/** Validated filters and paging controls for the administrative account list. */
public record AdminAccountQuery(
    int page,
    int size,
    String sort,
    Sort.Direction direction,
    AccountStatus status,
    Role role,
    String text
) {
  private static final int DEFAULT_SIZE = 25;
  private static final int MAX_SIZE = 100;
  private static final int MAX_SEARCH_LENGTH = 100;
  private static final Set<String> ALLOWED_SORTS = Set.of(
      "createdOn",
      "lastUpdatedOn",
      "lastLoginOn",
      "username",
      "email",
      "status",
      "role");

  /** Parses request values into a safe query contract. */
  public static AdminAccountQuery from(
      Integer requestedPage,
      Integer requestedSize,
      String requestedSort,
      String requestedDirection,
      String requestedStatus,
      String requestedRole,
      String requestedText
  ) throws InvalidRequestException {
    var page = requestedPage == null ? 0 : requestedPage;
    var size = requestedSize == null ? DEFAULT_SIZE : requestedSize;
    if (page < 0) {
      throw new InvalidRequestException("Account page must not be negative.");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new InvalidRequestException("Account page size must be between 1 and 100.");
    }

    var sort = normalizeOptional(requestedSort);
    sort = sort == null ? "createdOn" : sort;
    if (!ALLOWED_SORTS.contains(sort)) {
      throw new InvalidRequestException("Unsupported account sort field.");
    }

    var direction = parseDirection(requestedDirection);
    var status = parseStatus(requestedStatus);
    var role = parseRole(requestedRole);
    var text = normalizeOptional(requestedText);
    if (text != null && text.length() > MAX_SEARCH_LENGTH) {
      throw new InvalidRequestException("Account search text must not exceed 100 characters.");
    }
    return new AdminAccountQuery(page, size, sort, direction, status, role, text);
  }

  private static Sort.Direction parseDirection(String value) throws InvalidRequestException {
    var normalized = normalizeOptional(value);
    if (normalized == null) {
      return Sort.Direction.DESC;
    }
    try {
      return Sort.Direction.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException("Unsupported account sort direction.", exception);
    }
  }

  private static AccountStatus parseStatus(String value) throws InvalidRequestException {
    var normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    try {
      return AccountStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException("Unsupported account status filter.", exception);
    }
  }

  private static Role parseRole(String value) throws InvalidRequestException {
    var normalized = normalizeOptional(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Role.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new InvalidRequestException("Unsupported account role filter.", exception);
    }
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.strip();
  }
}
