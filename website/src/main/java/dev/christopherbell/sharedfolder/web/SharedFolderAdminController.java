package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.audit.SharedFolderAuditEvent;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditFilter;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditQueryService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleEntry;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only audit and recycle administration boundary. Services repeat fresh persisted checks. */
@RestController
@RequestMapping("/api/shared-folder" + V20260717 + "/admin")
@PreAuthorize("hasAuthority('ADMIN')")
public class SharedFolderAdminController {
  private final SharedFolderAuditQueryService audit;
  private final SharedFolderRecycleService recycle;
  private final SharedFolderAuditRecorder recorder;

  public SharedFolderAdminController(
      SharedFolderAuditQueryService audit,
      SharedFolderRecycleService recycle,
      SharedFolderAuditRecorder recorder) {
    this.audit = audit;
    this.recycle = recycle;
    this.recorder = recorder;
  }

  @GetMapping("/audit")
  public ResponseEntity<List<SharedFolderAuditEvent>> audit(
      @RequestParam(required = false) String accountId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String outcome,
      @RequestParam(name = "path", required = false) String relativePath,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) Integer limit) {
    return ResponseEntity.ok().headers(noStore()).body(audited(
        "AUDIT_BROWSE", relativePath == null ? "audit" : relativePath,
        () -> audit.search(new SharedFolderAuditFilter(
            accountId, action, outcome, relativePath, from, to, limit))));
  }

  @GetMapping("/recycle")
  public ResponseEntity<List<SharedFolderRecycleEntry>> recycle() {
    return ResponseEntity.ok().headers(noStore())
        .body(audited("RECYCLE_BROWSE", "recycle", () -> recycle.list().stream()
            .map(SharedFolderRecycleEntry::from).toList()));
  }

  @PostMapping("/recycle/{id}/restore")
  public ResponseEntity<SharedDirectoryEntry> restore(
      @PathVariable String id, @Valid @RequestBody RestoreRequest request) {
    return ResponseEntity.ok().headers(noStore()).body(audited(
        "RESTORE", id, () -> recycle.restore(id, request.replace())));
  }

  @DeleteMapping("/recycle/{id}")
  public ResponseEntity<Void> purge(
      @PathVariable String id, @Valid @RequestBody PurgeRequest request) {
    audited("PURGE", id, () -> {
      recycle.purge(id, request.confirmation());
      return Boolean.TRUE;
    });
    return ResponseEntity.noContent().headers(noStore()).build();
  }

  private HttpHeaders noStore() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl("private, no-store");
    return headers;
  }

  private <T> T audited(String action, String resource, java.util.function.Supplier<T> operation) {
    T result = operation.get();
    if (action.equals("AUDIT_BROWSE") || action.equals("RECYCLE_BROWSE")) {
      recorder.recordCurrent(action, resource, null, "accepted", null);
    }
    return result;
  }

  public record RestoreRequest(@NotNull Boolean replace) {}

  public record PurgeRequest(@NotBlank String confirmation) {}
}
