package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.music.radio.MusicQueueService;
import dev.christopherbell.music.radio.MusicQueueView;
import dev.christopherbell.music.radio.MusicRadioService;
import dev.christopherbell.music.radio.MusicRadioSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Music-read station views and Music-write mutations of the one global queue. */
@RestController
@RequestMapping("/api/music" + V20260728)
public final class MusicRadioController {
  private final MusicRadioService radio;
  private final MusicQueueService queue;

  public MusicRadioController(MusicRadioService radio, MusicQueueService queue) {
    this.radio = radio;
    this.queue = queue;
  }

  @GetMapping("/radio")
  public ResponseEntity<MusicRadioSnapshot> radio() {
    return noStore(radio.current());
  }

  @GetMapping("/queue")
  public ResponseEntity<MusicQueueView> queue() {
    return noStore(queue.current());
  }

  @PostMapping("/queue")
  public ResponseEntity<MusicQueueView> add(@Valid @RequestBody AddRequest request) {
    return noStore(queue.add(request.trackId(), request.expectedVersion()));
  }

  @PatchMapping("/queue")
  public ResponseEntity<MusicQueueView> reorder(@Valid @RequestBody ReorderRequest request) {
    return noStore(queue.reorder(request.orderedIds(), request.expectedVersion()));
  }

  @DeleteMapping("/queue/{entryId}")
  public ResponseEntity<MusicQueueView> remove(
      @PathVariable @NotBlank @Size(max = 100) String entryId,
      @RequestParam @Min(0) long expectedVersion) {
    return noStore(queue.remove(entryId, expectedVersion));
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  public record AddRequest(
      @NotBlank @Size(max = 128) String trackId,
      @Min(0) long expectedVersion) {}

  public record ReorderRequest(
      @NotNull @Size(max = 1000) List<@NotBlank @Size(max = 100) String> orderedIds,
      @Min(0) long expectedVersion) {
    public ReorderRequest {
      orderedIds = orderedIds == null ? null : List.copyOf(orderedIds);
    }
  }
}
