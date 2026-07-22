package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewResponse;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.account.model.Account;
import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService.SharedFolderDownload;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService.SharedFolderPreview;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated read-only HTTP boundary for the private shared-folder portal. */
@RestController
@RequestMapping("/api/shared-folder" + V20260717)
public class SharedFolderReadController {
  private final SharedFolderAccessService access;
  private final SharedFolderBrowserService browser;
  private final SharedFolderDownloadService downloads;
  private final SharedFolderPreviewService previews;
  private final SharedFolderAuditRecorder audit;

  /** Creates the protected read-only endpoint group. */
  public SharedFolderReadController(
      SharedFolderAccessService access,
      SharedFolderBrowserService browser,
      SharedFolderDownloadService downloads,
      SharedFolderPreviewService previews,
      SharedFolderAuditRecorder audit) {
    this.access = access;
    this.browser = browser;
    this.downloads = downloads;
    this.previews = previews;
    this.audit = audit;
  }

  /** Lists a decoded relative directory path after refreshing effective read access. */
  @GetMapping("/entries")
  public ResponseEntity<SharedDirectoryResponse> list(@RequestParam(defaultValue = "") String path) {
    try {
      Account account = access.requireRead();
      SharedDirectoryResponse response = browser.list(path);
      audit.recordFor(account, "LIST", path, null, "accepted", null);
      return ResponseEntity.ok()
          .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
          .body(response);
    } catch (RuntimeException failure) {
      throw failure;
    }
  }

  /**
   * Streams a full file or one valid byte range without reading it into memory.
   *
   * <p>Spring decodes {@code path} once before this method. The value is deliberately passed to
   * the service unchanged so percent-encoded separators remain unsafe rather than being decoded
   * a second time.
   */
  @GetMapping("/content")
  public ResponseEntity<Resource> content(
      @RequestParam String path,
      @RequestHeader HttpHeaders headers) {
    try {
      access.requireRead();
      List<String> ranges = headers.get(HttpHeaders.RANGE);
      String range = ranges == null || ranges.isEmpty() ? null : String.join(",", ranges);
      SharedFolderDownload download = downloads.open(path, range);
      audit.recordLogicalAccess("DOWNLOAD_STARTED", path, download.totalLength());
      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
      responseHeaders.set("X-Content-Type-Options", "nosniff");
      responseHeaders.setCacheControl("private, no-store");
      responseHeaders.setContentType(download.mediaType());
      responseHeaders.set(HttpHeaders.CONTENT_DISPOSITION, download.disposition());
      responseHeaders.setContentLength(download.length());
      if (download.partial()) {
        long end = download.start() + download.length() - 1;
        responseHeaders.set(HttpHeaders.CONTENT_RANGE,
            "bytes " + download.start() + "-" + end + "/" + download.totalLength());
      }
      return new ResponseEntity<>(download.resource(), responseHeaders,
          download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    } catch (SharedFolderRangeNotSatisfiableException exception) {
      return rangeNotSatisfiable(exception.totalLength());
    } catch (RuntimeException failure) {
      throw failure;
    }
  }

  /** Returns a bounded text preview or a streamed allowlisted media preview. */
  @GetMapping("/preview")
  public ResponseEntity<?> preview(@RequestParam String path) {
    try {
      Account account = access.requireRead();
      SharedFolderPreview preview = previews.open(path);
      audit.recordFor(account, "PREVIEW_STARTED", path, null, "accepted", null);
      HttpHeaders headers = new HttpHeaders();
      headers.set("X-Content-Type-Options", "nosniff");
      headers.setCacheControl("private, no-store");
      if (preview.kind() == dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind.TEXT) {
        return ResponseEntity.ok().headers(headers)
            .body(new SharedFolderPreviewResponse(preview.text(), preview.truncated()));
      }
      headers.setContentType(preview.mediaType());
      headers.set(HttpHeaders.CONTENT_DISPOSITION, preview.disposition());
      if (preview.kind() == dev.christopherbell.sharedfolder.model.SharedFolderPreviewKind.PDF) {
        headers.set("Content-Security-Policy", "sandbox; default-src 'none'");
      }
      return new ResponseEntity<>(preview.resource(), headers, HttpStatus.OK);
    } catch (RuntimeException failure) {
      throw failure;
    }
  }

  private ResponseEntity<Resource> rangeNotSatisfiable(long totalLength) {
    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalLength)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .build();
  }
}
