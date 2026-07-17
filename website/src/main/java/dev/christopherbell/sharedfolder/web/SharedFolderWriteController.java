package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.model.SharedDirectoryEntry;
import dev.christopherbell.sharedfolder.model.SharedFolderCreateFolderRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderDeleteRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderMoveRequest;
import dev.christopherbell.sharedfolder.model.SharedFolderRenameRequest;
import dev.christopherbell.sharedfolder.service.SharedFolderMutationService;
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

  /** Creates the protected write controller. Services refresh persisted write access per operation. */
  public SharedFolderWriteController(
      SharedFolderMutationService mutations, SharedFolderUploadService uploads) {
    this.mutations = mutations;
    this.uploads = uploads;
  }

  /** Creates one direct child directory. */
  @PostMapping("/folders")
  public ResponseEntity<SharedDirectoryEntry> createFolder(
      @Valid @RequestBody SharedFolderCreateFolderRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).headers(noStore()).body(mutations.createFolder(request));
  }

  /** Renames one observed item. */
  @PatchMapping("/entries/rename")
  public ResponseEntity<SharedDirectoryEntry> rename(
      @Valid @RequestBody SharedFolderRenameRequest request) {
    return ResponseEntity.ok().headers(noStore()).body(mutations.rename(request));
  }

  /** Moves one observed item and requires explicit replacement intent. */
  @PostMapping("/entries/move")
  public ResponseEntity<SharedDirectoryEntry> move(@Valid @RequestBody SharedFolderMoveRequest request) {
    return ResponseEntity.ok().headers(noStore()).body(mutations.move(request));
  }

  /** Physically deletes one observed item until the later recycle layer replaces this route's seam. */
  @DeleteMapping("/entries")
  public ResponseEntity<Void> delete(@Valid @RequestBody SharedFolderDeleteRequest request) {
    mutations.delete(request);
    return ResponseEntity.noContent().headers(noStore()).build();
  }

  /** Creates an owned private-disk upload session. */
  @PostMapping("/uploads")
  public ResponseEntity<SharedFolderUploadStatus> createUpload(
      @Valid @RequestBody SharedFolderUploadCreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).headers(noStore()).body(uploads.create(request));
  }

  /** Returns owned upload progress. */
  @GetMapping("/uploads/{id}")
  public ResponseEntity<SharedFolderUploadStatus> uploadStatus(@PathVariable String id) {
    return ResponseEntity.ok().headers(noStore()).body(uploads.status(id));
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
    return ResponseEntity.ok().headers(noStore()).body(uploads.complete(id, request != null && request.replace()));
  }

  /** Cancels an owned upload and removes its private staging bytes. */
  @DeleteMapping("/uploads/{id}")
  public ResponseEntity<SharedFolderUploadStatus> cancelUpload(@PathVariable String id) {
    return ResponseEntity.ok().headers(noStore()).body(uploads.cancel(id));
  }

  private HttpHeaders noStore() {
    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl("private, no-store");
    return headers;
  }
}
