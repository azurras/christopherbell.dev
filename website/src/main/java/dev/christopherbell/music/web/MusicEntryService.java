package dev.christopherbell.music.web;

import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.music.security.MusicAccessAuditRecorder;
import dev.christopherbell.music.security.MusicAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves public Music entry state and records denied identities. */
@Service
public class MusicEntryService {
  private final MusicAccessService access;
  private final MusicAccessAuditRecorder audit;
  private final ClientIpResolver clientIps;

  public MusicEntryService(
      MusicAccessService access,
      MusicAccessAuditRecorder audit,
      ClientIpResolver clientIps) {
    this.access = access;
    this.audit = audit;
    this.clientIps = clientIps;
  }

  public MusicAccessStatus status(HttpServletRequest request) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean authenticated = authentication != null && authentication.isAuthenticated()
        && !"anonymousUser".equals(authentication.getName());
    if (!authenticated) {
      audit.deniedIp(clientIps.resolveClientIp(request), "SIGN_IN_REQUIRED");
      return new MusicAccessStatus(false, false, false, "SIGN_IN_REQUIRED");
    }
    try {
      var account = access.requireRead();
      boolean canManage = access.effectivePermissions(account)
          .contains(AccountPermission.MUSIC_WRITE);
      return new MusicAccessStatus(true, true, canManage, null);
    } catch (AccessDeniedException denied) {
      audit.deniedAccount(authentication.getName(), "MUSIC_READ_REQUIRED");
      return new MusicAccessStatus(true, false, false, "MUSIC_READ_REQUIRED");
    }
  }
}
