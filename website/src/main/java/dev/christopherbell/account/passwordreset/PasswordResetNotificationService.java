package dev.christopherbell.account.passwordreset;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.configuration.mail.MailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends password reset links when mail is configured.
 */
@RequiredArgsConstructor
@Service
@Slf4j
public class PasswordResetNotificationService {
  private final ObjectProvider<JavaMailSender> mailSenderProvider;
  private final MailProperties mailProperties;

  public void sendPasswordReset(Account account, String resetUrl) {
    if (!mailProperties.enabled()) {
      log.info("Password reset email for account {} was not sent because mail is disabled.",
          account.getId());
      return;
    }
    var mailSender = mailSenderProvider.getIfAvailable();
    if (mailSender == null) {
      log.warn("Password reset email for account {} was not sent because mail is not configured.",
          account.getId());
      return;
    }

    var message = new SimpleMailMessage();
    message.setFrom(mailProperties.from());
    message.setTo(account.getEmail());
    message.setSubject("Reset your password");
    message.setText("""
        A password reset was requested for your account.

        Use this link to set a new password:
        %s

        This link expires in 1 hour. If you did not request this, you can ignore this email.
        """.formatted(resetUrl));
    try {
      mailSender.send(message);
    } catch (MailException e) {
      log.error("Unable to send password reset email for account {}.", account.getId(), e);
    }
  }
}
