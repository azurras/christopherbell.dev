package dev.christopherbell.admin.activity;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import org.springframework.stereotype.Service;

/** Validates and reads stable append-only audit pages. */
@Service
public class AdminActivityQueryService {
  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_FILTER_LENGTH = 64;
  private final AdminActivityQueryPort activities;

  public AdminActivityQueryService(AdminActivityQueryPort activities) {
    this.activities = activities;
  }

  /** Executes allowlisted equality and escaped actor filters. */
  public AdminActivityPage query(AdminActivityQuery request) throws InvalidRequestException {
    validate(request);
    return activities.query(request);
  }

  private void validate(AdminActivityQuery request) throws InvalidRequestException {
    if (request == null || request.page() < 0 || request.size() < 1
        || request.size() > MAX_PAGE_SIZE
        || tooLong(request.action()) || tooLong(request.targetType()) || tooLong(request.actor())) {
      throw new InvalidRequestException("Invalid audit query.");
    }
    if ((request.from() == null) != (request.to() == null)
        || (request.from() != null && request.from().isAfter(request.to()))) {
      throw new InvalidRequestException("Invalid audit date range.");
    }
  }

  private boolean tooLong(String value) {
    return value != null && value.strip().length() > MAX_FILTER_LENGTH;
  }

}
