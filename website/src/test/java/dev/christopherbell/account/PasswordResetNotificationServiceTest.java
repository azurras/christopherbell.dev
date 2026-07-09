package dev.christopherbell.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.passwordreset.PasswordResetNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PasswordResetNotificationServiceTest {
  @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
  @Mock private JavaMailSender mailSender;

  @Test
  void sendPasswordReset_whenMailSenderMissing_doesNotLogResetUrlOrToken(CapturedOutput output) {
    var service = new PasswordResetNotificationService(mailSenderProvider);
    var account = Account.builder()
        .id("acc-1")
        .email("user@example.com")
        .build();

    when(mailSenderProvider.getIfAvailable()).thenReturn(null);

    service.sendPasswordReset(account, "https://example.com/reset-password?token=super-secret-token");

    assertThat(output).contains("acc-1");
    assertThat(output).doesNotContain("super-secret-token");
    assertThat(output).doesNotContain("https://example.com/reset-password");
  }

  @Test
  void sendPasswordReset_whenMailAuthenticationFails_doesNotThrow(CapturedOutput output) {
    var service = new PasswordResetNotificationService(mailSenderProvider);
    var account = Account.builder()
        .id("acc-1")
        .email("user@example.com")
        .build();

    when(mailSenderProvider.getIfAvailable()).thenReturn(mailSender);
    doThrow(new MailAuthenticationException("Authentication failed"))
        .when(mailSender)
        .send(any(SimpleMailMessage.class));

    service.sendPasswordReset(account, "https://example.com/reset-password?token=abc");

    verify(mailSender).send(any(SimpleMailMessage.class));
    assertThat(output).contains("acc-1");
    assertThat(output).doesNotContain("token=abc");
    assertThat(output).doesNotContain("https://example.com/reset-password");
  }
}
