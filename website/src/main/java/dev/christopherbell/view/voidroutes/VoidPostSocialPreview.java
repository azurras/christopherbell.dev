package dev.christopherbell.view.voidroutes;

import dev.christopherbell.post.model.PostFeedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Bounded public metadata derived from one active Void post. */
public record VoidPostSocialPreview(String title, String description) {
  private static final int MAX_EXCERPT_CODE_POINTS = 160;
  private static final int MAX_USERNAME_CODE_POINTS = 40;
  private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

  /** Creates safe-to-escape metadata without changing active-post visibility rules. */
  static VoidPostSocialPreview from(PostFeedItem post, Instant now) {
    String username = bounded(normalize(post.username()), MAX_USERNAME_CODE_POINTS);
    String title = username.isBlank() ? "A post in the Void" : "@" + username + " in the Void";
    String text = normalize(post.text());
    String excerpt = text.isBlank()
        ? "A temporary Void thread"
        : boundedWithEllipsis(text, MAX_EXCERPT_CODE_POINTS);
    String remaining = remainingLifespan(post.expiresOn(), now);
    String temporary = remaining.isBlank()
        ? "Temporary thread."
        : "Temporary thread with " + remaining + " remaining.";
    return new VoidPostSocialPreview(
        title,
        excerpt + " · " + temporary + " Keep it alive or let it disappear.");
  }

  private static String remainingLifespan(Instant expiresOn, Instant now) {
    if (expiresOn == null || !expiresOn.isAfter(now)) {
      return "";
    }
    long seconds = Math.max(1L, Duration.between(now, expiresOn).getSeconds());
    long minutes = Math.max(1L, (seconds + 59L) / 60L);
    if (minutes < 60L) {
      return minutes + "m";
    }
    long hours = (minutes + 59L) / 60L;
    if (hours < 48L) {
      return hours + "h";
    }
    return ((hours + 23L) / 24L) + "d";
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    var cleaned = new StringBuilder(value.length());
    value.codePoints().forEach(codePoint ->
        cleaned.appendCodePoint(Character.isISOControl(codePoint) ? ' ' : codePoint));
    return WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
  }

  private static String bounded(String value, int maxCodePoints) {
    int count = value.codePointCount(0, value.length());
    if (count <= maxCodePoints) {
      return value;
    }
    return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
  }

  private static String boundedWithEllipsis(String value, int maxCodePoints) {
    int count = value.codePointCount(0, value.length());
    if (count <= maxCodePoints) {
      return value;
    }
    int contentCodePoints = maxCodePoints - 1;
    return value.substring(0, value.offsetByCodePoints(0, contentCodePoints)) + "…";
  }
}
