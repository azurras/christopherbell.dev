package dev.christopherbell.configuration;

import com.mongodb.ConnectionString;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Rejects incomplete or unsafe production settings before the context is refreshed. */
public final class ProductionSettingsApplicationContextInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {
  private static final Pattern EMAIL = Pattern.compile(
      "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
  private static final List<String> PLACEHOLDER_FRAGMENTS = List.of(
      "replace-with", "your_resend", "your-verified-domain", "example");

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    var environment = context.getEnvironment();
    if (!environment.acceptsProfiles(Profiles.of("prod"))) {
      return;
    }

    var violations = new ArrayList<String>();
    validatePersistence(environment, violations);
    validateJwt(environment, violations);
    validateMail(environment, violations);
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Invalid production configuration:\n- " + String.join("\n- ", violations));
    }
  }

  private static void validatePersistence(Environment environment, List<String> violations) {
    var backend = trimmed(environment, "APP_PERSISTENCE_BACKEND");
    if (backend.equalsIgnoreCase("mongodb")) {
      validateMongo(environment, violations);
      return;
    }
    if (backend.equalsIgnoreCase("postgresql")) {
      validateRequired(environment, "SPRING_DATASOURCE_URL", violations);
      validateRequired(environment, "SPRING_DATASOURCE_USERNAME", violations);
      validateRequired(environment, "SPRING_DATASOURCE_PASSWORD", violations);
      return;
    }
    violations.add("APP_PERSISTENCE_BACKEND must be mongodb or postgresql.");
  }

  private static void validateRequired(Environment environment, String key, List<String> violations) {
    if (trimmed(environment, key).isEmpty()) {
      violations.add(key + " is required.");
    }
  }

  private static void validateMongo(Environment environment, List<String> violations) {
    var value = trimmed(environment, "SPRING_MONGODB_URI");
    if (value.isEmpty()) {
      violations.add("SPRING_MONGODB_URI is required.");
      return;
    }
    try {
      new ConnectionString(value);
    } catch (RuntimeException ignored) {
      violations.add("SPRING_MONGODB_URI must be a valid MongoDB connection string.");
    }
  }

  private static void validateJwt(Environment environment, List<String> violations) {
    var value = trimmed(environment, "APP_JWT_SECRET");
    if (value.length() < 32 || isPlaceholder(value)) {
      violations.add("APP_JWT_SECRET must be a non-placeholder value of at least 32 characters.");
    }
  }

  private static void validateMail(Environment environment, List<String> violations) {
    var switchValue = trimmed(environment, "APP_MAIL_ENABLED");
    var enabled = switchValue.isEmpty() || switchValue.equalsIgnoreCase("true");
    if (!switchValue.isEmpty()
        && !switchValue.equalsIgnoreCase("true")
        && !switchValue.equalsIgnoreCase("false")) {
      violations.add("APP_MAIL_ENABLED must be true or false.");
    }
    if (!enabled) {
      return;
    }

    var from = trimmed(environment, "APP_MAIL_FROM");
    if (!EMAIL.matcher(from).matches() || isPlaceholder(from)) {
      violations.add("APP_MAIL_FROM must be a valid non-placeholder email address.");
    }
    var key = trimmed(environment, "RESEND_API_KEY");
    if (key.isEmpty() || isPlaceholder(key)) {
      violations.add("RESEND_API_KEY must be a non-placeholder value.");
    }
  }

  private static String trimmed(Environment environment, String key) {
    var value = environment.getProperty(key);
    return value == null ? "" : value.trim();
  }

  private static boolean isPlaceholder(String value) {
    var normalized = value.toLowerCase(Locale.ROOT);
    return PLACEHOLDER_FRAGMENTS.stream().anyMatch(normalized::contains);
  }
}
