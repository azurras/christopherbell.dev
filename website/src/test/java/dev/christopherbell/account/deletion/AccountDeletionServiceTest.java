package dev.christopherbell.account.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.libs.api.exception.InvalidRequestException;
import dev.christopherbell.libs.api.exception.ServiceUnavailableException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {
  @Mock private AccountDeletionJobRepository jobs;
  @Mock private AccountDeletionOperations operations;
  private AccountDeletionService service;

  @BeforeEach
  void setUp() {
    service = new AccountDeletionService(jobs, operations);
  }

  @Test
  @DisplayName("Deletion executes every privacy step in order and stores only a pseudonym")
  void delete_executesOrderedStepsAndCompletesPseudonymousJob() throws Exception {
    var persisted = new AtomicReference<AccountDeletionJob>();
    when(jobs.findById(any())).thenReturn(Optional.empty());
    when(jobs.save(any())).thenAnswer(invocation -> {
      var job = invocation.getArgument(0, AccountDeletionJob.class);
      persisted.set(job.copy());
      return job;
    });
    when(operations.accountExists("account-123")).thenReturn(true);

    var result = service.delete("account-123");

    var ordered = inOrder(operations);
    ordered.verify(operations).accountExists("account-123");
    ordered.verify(operations).ensureTombstone();
    ordered.verify(operations).anonymizePublicPosts("account-123", result.pseudonym());
    ordered.verify(operations).removePrivateData("account-123");
    ordered.verify(operations).cleanSharedFolderState("account-123");
    ordered.verify(operations).pseudonymizeRetainedRecords(
        "account-123", result.pseudonym());
    ordered.verify(operations).removeReferencesAndAccount("account-123");
    assertThat(result.status()).isEqualTo(AccountDeletionStatus.COMPLETE);
    assertThat(result.completedSteps()).isEqualTo(AccountDeletionStep.values().length);
    assertThat(result.pseudonym()).matches("deleted:[a-f0-9]{12}");
    assertThat(persisted.get().toString()).doesNotContain("account-123");
    assertThat(persisted.get().getStatus()).isEqualTo(AccountDeletionStatus.COMPLETE);
  }

  @Test
  @DisplayName("Deletion records a safe failure and resumes from the failed idempotent step")
  void delete_afterPartialFailure_resumesWithoutRepeatingCheckpoints() throws Exception {
    var persisted = new AtomicReference<AccountDeletionJob>();
    when(jobs.findById(any())).thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
    when(jobs.save(any())).thenAnswer(invocation -> {
      var job = invocation.getArgument(0, AccountDeletionJob.class);
      persisted.set(job.copy());
      return job;
    });
    when(operations.accountExists("account-456")).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("private database detail"))
        .doNothing()
        .when(operations).removePrivateData("account-456");

    assertThatThrownBy(() -> service.delete("account-456"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Account deletion is temporarily unavailable.")
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(persisted.get().getStatus()).isEqualTo(AccountDeletionStatus.FAILED);
    assertThat(persisted.get().getFailureCategory()).isEqualTo("private_data");
    assertThat(persisted.get().getNextStep()).isEqualTo(AccountDeletionStep.REMOVE_PRIVATE_DATA);

    clearInvocations(operations);
    var result = service.delete("account-456");

    verify(operations, never()).ensureTombstone();
    verify(operations, never()).anonymizePublicPosts(any(), any());
    verify(operations).removePrivateData("account-456");
    verify(operations).removeReferencesAndAccount("account-456");
    assertThat(result.status()).isEqualTo(AccountDeletionStatus.COMPLETE);
  }

  @Test
  @DisplayName("Completed deletion is idempotent and does not re-enter cleanup")
  void delete_whenJobComplete_returnsStoredResultWithoutEffects() throws Exception {
    var pseudonym = AccountDeletionService.pseudonymFor("account-789");
    var completed = AccountDeletionJob.started(pseudonym);
    completed.complete();
    when(jobs.findById(pseudonym)).thenReturn(Optional.of(completed));

    var first = service.delete("account-789");
    var second = service.delete("account-789");

    assertThat(first).isEqualTo(second);
    assertThat(first.status()).isEqualTo(AccountDeletionStatus.COMPLETE);
    verify(operations, never()).accountExists(any());
  }

  @Test
  @DisplayName("Deletion rejects blank and tombstone identifiers")
  void delete_rejectsInvalidTargets() {
    assertThatThrownBy(() -> service.delete(" "))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("Account id is required for deletion.");
    assertThatThrownBy(() -> service.delete("deleted-user"))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("The deleted-user tombstone cannot be deleted.");
  }

  @Test
  @DisplayName("Deletion translates an unavailable durable job store into a safe 503 failure")
  void delete_whenJobStoreUnavailable_preservesCauseWithoutLeakingDetails() {
    var databaseFailure = new IllegalStateException("mongodb host and collection detail");
    when(jobs.findById(any())).thenThrow(databaseFailure);

    assertThatThrownBy(() -> service.delete("account-999"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Account deletion is temporarily unavailable.")
        .hasCause(databaseFailure);
  }
}
