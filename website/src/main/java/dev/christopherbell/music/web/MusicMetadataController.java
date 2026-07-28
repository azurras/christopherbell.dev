package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.music.metadata.MusicMetadataResult;
import dev.christopherbell.music.metadata.MusicMetadataService;
import dev.christopherbell.music.metadata.MusicMetadataUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Music-write HTTP boundary for revision-checked metadata edits and undo. */
@RestController
@RequestMapping("/api/music" + V20260728)
public final class MusicMetadataController {
  private final MusicMetadataService metadata;

  public MusicMetadataController(MusicMetadataService metadata) {
    this.metadata = metadata;
  }

  @PatchMapping("/tracks/{id}/metadata")
  public ResponseEntity<MusicMetadataResult> edit(
      @PathVariable @NotBlank @Size(max = 128) String id,
      @NotNull @Valid @RequestBody MusicMetadataUpdate request) {
    return noStore(metadata.edit(id, request));
  }

  @PostMapping("/metadata-edits/{id}/undo")
  public ResponseEntity<MusicMetadataResult> undo(
      @PathVariable @NotBlank @Size(max = 128) String id,
      @NotNull @Valid @RequestBody UndoRequest request) {
    return noStore(metadata.undo(id, request.expectedObservedToken()));
  }

  private <T> ResponseEntity<T> noStore(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }

  public record UndoRequest(@NotBlank @Size(max = 128) String expectedObservedToken) {
  }
}
