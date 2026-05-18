package dev.christopherbell.account;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class PasswordResetNotificationServiceTest {
  @Mock private ObjectProvider<JavaMailSender> mailSenderProvider;
  @Mock private JavaMailSender mailSender;

  @Test
  void sendPasswordReset_whenMailAuthenticationFails_doesNotThrow() {
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
  }
}
