package dev.christopherbell.music.web;

import static dev.christopherbell.libs.api.APIVersion.V20260728;

import dev.christopherbell.music.security.MusicAccessAttempt;
import dev.christopherbell.music.security.MusicAccessAuditQueryService;
import dev.christopherbell.music.security.MusicAccessService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Fresh-admin-only denied Music access log. */
@RestController
@RequestMapping("/api/music" + V20260728 + "/admin")
public final class MusicAdminController {
  private final MusicAccessService access;
  private final MusicAccessAuditQueryService audit;

  public MusicAdminController(MusicAccessService access, MusicAccessAuditQueryService audit) {
    this.access = access;
    this.audit = audit;
  }

  @GetMapping("/access-attempts")
  public ResponseEntity<List<MusicAccessAttempt>> accessAttempts(
      @RequestParam(defaultValue = "100") int limit) {
    access.requireAdmin();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(audit.recent(limit));
  }
}
