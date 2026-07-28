package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public Music entry probe; all catalog and playback routes remain protected. */
@RestController
@RequestMapping("/api/music" + V20260728)
public class MusicAccessController {
  private final MusicEntryService entry;

  public MusicAccessController(MusicEntryService entry) {
    this.entry = entry;
  }

  @GetMapping("/access")
  public ResponseEntity<MusicAccessStatus> access(HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(entry.status(request));
  }
}
