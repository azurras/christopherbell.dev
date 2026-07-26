package dev.christopherbell.configuration.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application mail delivery settings. */
@ConfigurationProperties("app.mail")
public record MailProperties(boolean enabled, String from) {
  public MailProperties {
    from = from == null ? "" : from.trim();
  }
}
