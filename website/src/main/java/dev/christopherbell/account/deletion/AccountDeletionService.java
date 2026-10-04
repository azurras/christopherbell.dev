package dev.christopherbell.account.deletion;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ResourceNotFoundException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import dev.christopherbell.libs.lease.LeaseStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Coordinates comprehensive account deletion through durable idempotent checkpoints. */
@RequiredArgsConstructor
@Service
public class AccountDeletionService {
  static final String TOMBSTONE_ID = "deleted-user";
  private static final int MAX_ACCOUNT_ID_LENGTH = 128;
  private static final String UNAVAILABLE_MESSAGE = "Account deletion is temporarily unavailable.";

  private final AccountDeletionJobRepository jobs;
  private final AccountDeletionOperations operations;
  private final LeaseStore leases;

  /** A started or failed deletion also forbids creating new private monitoring data. */
  public boolean hasStarted(String accountId) {
    return jobs.findById(pseudonymFor(accountId)).isPresent();
  }

  /** Creates or resumes a deletion job, returning only its stable pseudonymous identity. */
  public AccountDeletionResult delete(String rawAccountId)
      throws InvalidRequestException, ResourceNotFoundException {
    var accountId = validateAccountId(rawAccountId);
    // Serialize the deletion marker and monitor cleanup with in-flight monitor saves.
    var grant = leases.tryAcquire("site-monitor-pilot", java.util.UUID.randomUUID().toString(),
        java.time.Duration.ofMinutes(3))
        .orElseThrow(() -> new ServiceUnavailableException(UNAVAILABLE_MESSAGE, null));
    try {
      return deleteValidated(accountId, grant);
    } finally {
      leases.release(grant);
    }
  }

  private AccountDeletionResult deleteValidated(String accountId, dev.christopherbell.libs.lease.LeaseGrant grant)
      throws ResourceNotFoundException {
    var pseudonym = pseudonymFor(accountId);
    final Optional<AccountDeletionJob> existing;
    try {
      existing = jobs.findById(pseudonym);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    if (existing.isPresent() && existing.get().getStatus() == AccountDeletionStatus.COMPLETE) {
      return existing.get().result();
    }

    AccountDeletionJob job;
    if (existing.isPresent()) {
      job = existing.get();
    } else {
      final boolean accountExists;
      try {
        accountExists = operations.accountExists(accountId);
      } catch (RuntimeException failure) {
        throw unavailable(failure);
      }
      if (!accountExists) {
        throw new ResourceNotFoundException("Account was not found.");
      }
      job = AccountDeletionJob.started(pseudonym);
    }
    job.resume();
    try {
      requireHeld(grant);
      jobs.save(job);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    while (job.getNextStep() != null) {
      var step = job.getNextStep();
      try {
        requireHeld(grant);
        step.execute(operations, accountId, pseudonym);
        job.advance();
        jobs.save(job);
      } catch (RuntimeException failure) {
        job.fail(step.failureCategory());
        try {
          jobs.save(job);
        } catch (RuntimeException checkpointFailure) {
          failure.addSuppressed(checkpointFailure);
        }
        throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE, failure);
      }
    }
    return job.result();
  }

  private void requireHeld(dev.christopherbell.libs.lease.LeaseGrant grant) {
    if (leases.renew(grant, java.time.Duration.ofMinutes(3)).isEmpty()) {
      throw new ServiceUnavailableException(UNAVAILABLE_MESSAGE, null);
    }
  }

  private ServiceUnavailableException unavailable(RuntimeException cause) {
    return new ServiceUnavailableException(UNAVAILABLE_MESSAGE, cause);
  }

  static String pseudonymFor(String accountId) {
    try {
      var digest = MessageDigest.getInstance("SHA-256")
          .digest(accountId.getBytes(StandardCharsets.UTF_8));
      return "deleted:" + HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private String validateAccountId(String value) throws InvalidRequestException {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException("Account id is required for deletion.");
    }
    var accountId = value.strip();
    if (TOMBSTONE_ID.equals(accountId)) {
      throw new InvalidRequestException("The deleted-user tombstone cannot be deleted.");
    }
    if (accountId.length() > MAX_ACCOUNT_ID_LENGTH
        || accountId.chars().anyMatch(Character::isISOControl)) {
      throw new InvalidRequestException("Account id is invalid for deletion.");
    }
    return accountId;
  }
}
