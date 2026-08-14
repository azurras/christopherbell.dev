package dev.christopherbell.music.security;

import java.util.List;
import org.springframework.stereotype.Service;

/** Returns a bounded newest-first view of denied Music entry attempts for administrators. */
@Service
public final class MusicAccessAuditQueryService {
  private final MusicAccessAttemptRepository attempts;

  public MusicAccessAuditQueryService(MusicAccessAttemptRepository attempts) {
    this.attempts = attempts;
  }

  public List<MusicAccessAttempt> recent(int requestedLimit) {
    return attempts.recent(Math.max(1, Math.min(100, requestedLimit)));
  }
}
