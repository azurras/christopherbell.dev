package dev.christopherbell.sharedfolder.web;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.model.SharedDirectoryResponse;
import dev.christopherbell.sharedfolder.model.SharedFolderPreviewResponse;
import dev.christopherbell.sharedfolder.security.SharedFolderAccessService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService;
import dev.christopherbell.sharedfolder.service.SharedFolderDownloadService.SharedFolderDownload;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService;
import dev.christopherbell.sharedfolder.service.SharedFolderPreviewService.SharedFolderPreview;
import dev.christopherbell.sharedfolder.service.SharedFolderBrowserService;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.util.List;
import org.springframework.core.io.support.ResourceRegion;
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

  /** Creates the protected read-only endpoint group. */
  public SharedFolderReadController(
      SharedFolderAccessService access,
      SharedFolderBrowserService browser,
      SharedFolderDownloadService downloads,
      SharedFolderPreviewService previews) {
    this.access = access;
    this.browser = browser;
    this.downloads = downloads;
    this.previews = previews;
  }

  /** Lists a decoded relative directory path after refreshing effective read access. */
  @GetMapping("/entries")
  public ResponseEntity<SharedDirectoryResponse> list(@RequestParam(defaultValue = "") String path) {
    access.requireRead();
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(browser.list(path));
  }

  /**
   * Streams a full file or one valid byte range without reading it into memory.
   *
   * <p>Spring decodes {@code path} once before this method. The value is deliberately passed to
   * the service unchanged so percent-encoded separators remain unsafe rather than being decoded
   * a second time.
   */
  @GetMapping("/content")
  public ResponseEntity<ResourceRegion> content(
      @RequestParam String path,
      @RequestHeader HttpHeaders headers) {
    access.requireRead();
    List<String> ranges = headers.get(HttpHeaders.RANGE);
    String range = ranges == null || ranges.isEmpty() ? null : String.join(",", ranges);
    try {
      SharedFolderDownload download = downloads.open(path, range);
      ResourceRegion body = new ResourceRegion(
          download.resource(), download.start(), download.length());
      HttpHeaders responseHeaders = new HttpHeaders();
      responseHeaders.set(HttpHeaders.ACCEPT_RANGES, "bytes");
      responseHeaders.set("X-Content-Type-Options", "nosniff");
      responseHeaders.setCacheControl("private, no-store");
      responseHeaders.setContentType(download.mediaType());
      responseHeaders.set(HttpHeaders.CONTENT_DISPOSITION, download.disposition());
      responseHeaders.setContentLength(download.length());
      if (download.partial()) {
        responseHeaders.set(HttpHeaders.CONTENT_RANGE,
            "bytes " + download.start() + "-" + (download.start() + download.length() - 1)
                + "/" + download.totalLength());
      }
      return new ResponseEntity<>(body, responseHeaders,
          download.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
    } catch (SharedFolderRangeNotSatisfiableException exception) {
      return rangeNotSatisfiable(exception.totalLength());
    }
  }

  /** Returns a bounded text preview or a streamed allowlisted media preview. */
  @GetMapping("/preview")
  public ResponseEntity<?> preview(@RequestParam String path) {
    access.requireRead();
    SharedFolderPreview preview = previews.open(path);
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
  }

  private ResponseEntity<ResourceRegion> rangeNotSatisfiable(long totalLength) {
    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + totalLength)
        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
        .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .build();
  }
}
