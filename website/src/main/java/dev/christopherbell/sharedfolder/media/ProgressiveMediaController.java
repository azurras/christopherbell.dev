package dev.christopherbell.sharedfolder.media;

import static dev.christopherbell.libs.api.APIVersion.V20260717;

import dev.christopherbell.sharedfolder.audit.SharedFolderAuditRecorder;
import dev.christopherbell.sharedfolder.service.SharedFolderRangeNotSatisfiableException;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Authenticated owned media job API and progressive derivative delivery endpoint. */
@RestController
@RequestMapping("/api/shared-folder" + V20260717 + "/media")
public class ProgressiveMediaController {
  private final MediaPlaybackService media;
  private final ProgressiveMediaStreamer streamer;
  private final SharedFolderAuditRecorder audit;

  public ProgressiveMediaController(
      MediaPlaybackService media,
      ProgressiveMediaStreamer streamer,
      SharedFolderAuditRecorder audit) {
    this.media = media;
    this.streamer = streamer;
    this.audit = audit;
  }

  @PostMapping("/playback")
  public ResponseEntity<MediaPlaybackDescriptor> playback(@RequestBody PlaybackRequest request) {
    return noStore(media.playback(request.path()), HttpStatus.OK);
  }

  @PostMapping("/fallback")
  public ResponseEntity<MediaPlaybackDescriptor> fallback(@RequestBody FallbackRequest request) {
    MediaPlaybackDescriptor result = media.requestFallback(request.path(), request.profile());
    return noStore(result, result.status() == MediaJobStatus.READY ? HttpStatus.OK : HttpStatus.ACCEPTED);
  }

  @GetMapping("/jobs/{id}")
  public ResponseEntity<MediaPlaybackDescriptor> job(@PathVariable String id) {
    return noStore(media.job(id), HttpStatus.OK);
  }

  @DeleteMapping("/jobs/{id}")
  public ResponseEntity<MediaPlaybackDescriptor> cancel(@PathVariable String id) {
    return noStore(media.cancel(id), HttpStatus.OK);
  }

  @GetMapping("/jobs/{id}/stream")
  public ResponseEntity<?> stream(
      @PathVariable String id, @RequestHeader HttpHeaders requestHeaders) throws IOException {
    MediaJob job = media.requireVisibleJob(id);
    audit.recordLogicalAccess("MEDIA_STREAM_STARTED", job.getSourcePath(), job.getOutputBytes());
    if (job.getStatus() == MediaJobStatus.READY) {
      String range = joinedRange(requestHeaders);
      try {
        var selection = streamer.openReady(job, range);
        HttpHeaders headers = mediaHeaders(job);
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setContentLength(selection.length());
        if (selection.partial()) {
          headers.set(HttpHeaders.CONTENT_RANGE,
              "bytes " + selection.start() + "-" + (selection.start() + selection.length() - 1)
                  + "/" + selection.totalLength());
        }
        StreamingResponseBody body = output -> {
          try (selection) {
            streamer.copyReady(selection, output);
          }
        };
        return new ResponseEntity<>(body, headers,
            selection.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK);
      } catch (SharedFolderRangeNotSatisfiableException exception) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
            .headers(mediaHeaders(job))
            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
            .header(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.totalLength()).build();
      }
    }
    if (job.getStatus().terminal()) {
      HttpStatus status = job.getStatus() == MediaJobStatus.INSUFFICIENT_SPACE
          ? HttpStatus.INSUFFICIENT_STORAGE : HttpStatus.SERVICE_UNAVAILABLE;
      return ResponseEntity.status(status).headers(mediaHeaders(job)).build();
    }
    StreamingResponseBody body = output -> streamer.copyGrowing(job, output);
    return new ResponseEntity<>(body, mediaHeaders(job), HttpStatus.OK);
  }

  private HttpHeaders mediaHeaders(MediaJob job) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(job.getProfile().mediaType());
    headers.setCacheControl("private, no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    return headers;
  }

  private String joinedRange(HttpHeaders headers) {
    List<String> values = headers.get(HttpHeaders.RANGE);
    return values == null || values.isEmpty() ? null : String.join(",", values);
  }

  private <T> ResponseEntity<T> noStore(T body, HttpStatus status) {
    return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
        .body(body);
  }

  public record PlaybackRequest(String path) {}
  public record FallbackRequest(String path, MediaOutputProfile profile) {}
}
