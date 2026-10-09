package dev.christopherbell.account.deletion;

import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;

/** Pseudonymous durable checkpoint for retryable account deletion. */
@Data
@NoArgsConstructor
public class AccountDeletionJob {
  @Id private String id;
  private AccountDeletionStatus status;
  private AccountDeletionStep nextStep;
  private String failureCategory;
  private Instant createdOn;
  private Instant lastUpdatedOn;
  private Instant completedOn;

  public static AccountDeletionJob started(String pseudonym, Instant now) {
    var job = new AccountDeletionJob();
    job.id = pseudonym;
    job.status = AccountDeletionStatus.ACTIVE;
    job.nextStep = AccountDeletionStep.values()[0];
    job.createdOn = now;
    job.lastUpdatedOn = now;
    return job;
  }

  public void resume(Instant now) {
    status = AccountDeletionStatus.ACTIVE;
    failureCategory = null;
    lastUpdatedOn = now;
  }

  public void advance(Instant now) {
    int following = nextStep.ordinal() + 1;
    failureCategory = null;
    lastUpdatedOn = now;
    if (following >= AccountDeletionStep.values().length) {
      complete(now);
      return;
    }
    nextStep = AccountDeletionStep.values()[following];
  }

  public void fail(String category, Instant now) {
    status = AccountDeletionStatus.FAILED;
    failureCategory = category;
    lastUpdatedOn = now;
  }
  public void complete(Instant now) {
    status = AccountDeletionStatus.COMPLETE;
    nextStep = null;
    failureCategory = null;
    completedOn = now;
    lastUpdatedOn = completedOn;
  }

  public int completedSteps() {
    return nextStep == null ? AccountDeletionStep.values().length : nextStep.ordinal();
  }

  public AccountDeletionResult result() {
    return new AccountDeletionResult(id, status, completedSteps());
  }

  public AccountDeletionJob copy() {
    var copy = new AccountDeletionJob();
    copy.id = id;
    copy.status = status;
    copy.nextStep = nextStep;
    copy.failureCategory = failureCategory;
    copy.createdOn = createdOn;
    copy.lastUpdatedOn = lastUpdatedOn;
    copy.completedOn = completedOn;
    return copy;
  }
}
