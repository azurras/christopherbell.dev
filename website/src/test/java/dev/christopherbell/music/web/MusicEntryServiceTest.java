package dev.christopherbell.music.web;

import static dev.christopherbell.account.model.AccountPermission.MUSIC_READ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.ClientIpResolver;
import dev.christopherbell.music.security.MusicAccessAuditRecorder;
import dev.christopherbell.music.security.MusicAccessService;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MusicEntryServiceTest {
  private final MusicAccessService access = mock(MusicAccessService.class);
  private final MusicAccessAuditRecorder audit = mock(MusicAccessAuditRecorder.class);
  private final ClientIpResolver clientIps = mock(ClientIpResolver.class);
  private final MusicEntryService service = new MusicEntryService(access, audit, clientIps);

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void anonymousAttemptRecordsOnlyResolvedClientIp() {
    var request = new MockHttpServletRequest();
    when(clientIps.resolveClientIp(request)).thenReturn("203.0.113.8");

    assertThat(service.status(request)).isEqualTo(
        new MusicAccessStatus(false, false, false, "SIGN_IN_REQUIRED"));

    verify(audit).deniedIp("203.0.113.8", "SIGN_IN_REQUIRED");
    verifyNoInteractions(access);
  }

  @Test
  void authenticatedDeniedAttemptRecordsAccountId() {
    authenticate("account-7");
    when(access.requireRead()).thenThrow(new AccessDeniedException("denied"));

    assertThat(service.status(new MockHttpServletRequest())).isEqualTo(
        new MusicAccessStatus(true, false, false, "MUSIC_READ_REQUIRED"));

    verify(audit).deniedAccount("account-7", "MUSIC_READ_REQUIRED");
  }

  @Test
  void authorizedListenerIsNotWrittenToDeniedLog() {
    authenticate("account-9");
    var account = Account.builder().id("account-9").permissions(Set.of(MUSIC_READ)).build();
    when(access.requireRead()).thenReturn(account);
    when(access.effectivePermissions(account)).thenReturn(Set.of(MUSIC_READ));

    assertThat(service.status(new MockHttpServletRequest())).isEqualTo(
        new MusicAccessStatus(true, true, false, null));

    verifyNoInteractions(audit);
  }

  private void authenticate(String accountId) {
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(accountId, "redacted", Set.of()));
  }
}
