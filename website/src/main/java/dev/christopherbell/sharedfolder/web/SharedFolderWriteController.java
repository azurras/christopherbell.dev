package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
import dev.christopherbell.sharedfolder.recycle.SharedFolderRecycleService;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadCompleteRequest;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadCreateRequest;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadService;
import dev.christopherbell.sharedfolder.upload.SharedFolderUploadStatus;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Protected write-only HTTP boundary for conflict-safe mutations and resumable uploads. */
@RestController
@RequestMapping("/api/shared-folder" + V20260717)
@PreAuthorize("isAuthenticated()")
public class SharedFolderWriteController {
  private static final String CHUNK_DIGEST_HEADER = "X-Chunk-SHA-256";

  private final SharedFolderMutationService mutations;
  private final SharedFolderUploadService uploads;
  private final SharedFolderRecycleService recycle;
  private final SharedFolderAuditRecorder audit;

  /** Creates the protected write controller. Services refresh persisted write access per operation. */
  public SharedFolderWriteController(
      SharedFolderMutationService mutations,
      SharedFolderUploadService uploads,
      SharedFolderRecycleService recycle,
      SharedFolderAuditRecorder audit) {
    this.mutations = mutations;
    this.uploads = uploads;
    this.recycle = recycle;
    this.audit = audit;
  }

  /** Creates one direct child directory. */
  @PostMapping("/folders")
  public ResponseEntity<SharedDirectoryEntry> createFolder(
      @Valid @RequestBody SharedFolderCreateFolderRequest request) {
    String requestedPath = request.parentPath().isEmpty() ? request.name()
        : request.parentPath() + "/" + request.name();
    SharedDirectoryEntry result = audited(
        "CREATE_FOLDER", requestedPath, () -> mutations.createFolder(request));
    audit.recordCurrent("CREATE_FOLDER", result.path(), 0L, "accepted", null);
    return ResponseEntity.status(HttpStatus.CREATED).headers(noStore()).body(result);
  }

  /** Renames one observed item. */
  @PatchMapping("/entries/rename")
  public ResponseEntity<SharedDirectoryEntry> rename(
      @Valid @RequestBody SharedFolderRenameRequest request) {
    SharedDirectoryEntry result = audited(
        "RENAME", request.path(), () -> mutations.rename(request));
    audit.recordCurrent("RENAME", result.path(), result.size(), "accepted", null);
    return ResponseEntity.ok().headers(noStore()).body(result);
  }

  /** Moves one observed item and requires explicit replacement intent. */
  @PostMapping("/entries/move")
  public ResponseEntity<SharedDirectoryEntry> move(@Valid @RequestBody SharedFolderMoveRequest request) {
    SharedDirectoryEntry result = audited(
        "MOVE", request.path(), () -> mutations.move(request));
    audit.recordCurrent("MOVE", result.path(), result.size(), "accepted", null);
    return ResponseEntity.ok().headers(noStore()).body(result);
  }

  /** Moves one observed item into isolated recoverable storage. */
  @DeleteMapping("/entries")
  public ResponseEntity<Void> delete(@Valid @RequestBody SharedFolderDeleteRequest request) {
    audited("RECYCLE", request.path(), () -> recycle.recycle(request));
    return ResponseEntity.noContent().headers(noStore()).build();
  }

  /** Creates an owned private-disk upload session. */
  @PostMapping("/uploads")
  public ResponseEntity<SharedFolderUploadStatus> createUpload(
      @Valid @RequestBody SharedFolderUploadCreateRequest request) {
    String requestedPath = request.parentPath().isEmpty() ? request.name()
        : request.parentPath() + "/" + request.name();
    SharedFolderUploadStatus result = audited(
        "UPLOAD_START", requestedPath, () -> uploads.create(request));
    String path = result.parentPath().isEmpty() ? result.name() : result.parentPath() + "/" + result.name();
    audit.recordCurrent("UPLOAD_START", path, result.expectedBytes(), "accepted", null);
    return ResponseEntity.status(HttpStatus.CREATED).headers(noStore()).body(result);
  }

  /** Returns owned upload progress. */
  @GetMapping("/uploads/{id}")
  public ResponseEntity<SharedFolderUploadStatus> uploadStatus(@PathVariable String id) {
    SharedFolderUploadStatus result = audited(
        "UPLOAD_STATUS", id, () -> uploads.status(id));
    audit.recordCurrent("UPLOAD_STATUS", id, result.expectedBytes(), "accepted", null);
    return ResponseEntity.ok().headers(noStore()).body(result);
  }

  /** Streams exactly one bounded ordered upload chunk; Spring decodes the identifier once. */
  @PutMapping("/uploads/{id}/chunks/{offset}")
  public ResponseEntity<SharedFolderUploadStatus> appendUploadChunk(
      @PathVariable String id,
      @PathVariable long offset,
      @RequestHeader(CHUNK_DIGEST_HEADER) String digest,
      HttpServletRequest request) throws IOException {
    return ResponseEntity.ok().headers(noStore())
        .body(uploads.append(id, offset, request.getInputStream(), digest));
  }

  /** Atomically finalizes an owned upload with an explicit replacement flag. */
  @PostMapping("/uploads/{id}/complete")
  public ResponseEntity<SharedFolderUploadStatus> completeUpload(
      @PathVariable String id,
      @RequestBody(required = false) SharedFolderUploadCompleteRequest request) {
    SharedFolderUploadStatus result = audited(
        "UPLOAD_FINALIZE", id,
        () -> uploads.complete(id, request != null && request.replace()));
    audit.recordCurrent("UPLOAD_FINALIZE", id, result.expectedBytes(), "accepted", null);
    return ResponseEntity.ok().headers(noStore()).body(result);
  }

  /** Cancels an owned upload and removes its private staging bytes. */
  @DeleteMapping("/uploads/{id}")
  public ResponseEntity<SharedFolderUploadStatus> cancelUpload(@PathVariable String id) {
    SharedFolderUploadStatus result = audited(
        "UPLOAD_CANCEL", id, () -> uploads.cancel(id));
    audit.recordCurrent("UPLOAD_CANCEL", id, result.nextOffset(), "accepted", null);
    return ResponseEntity.ok().headers(noStore()).body(result);
  }

  private HttpHeaders noStore() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl("private, no-store");
    return headers;
  }

  private <T> T audited(String action, String resource, java.util.function.Supplier<T> operation) {
    try {
      return operation.get();
    } catch (RuntimeException failure) {
      audit.recordFailure(action, resource, failure);
      throw failure;
    }
  }
}
