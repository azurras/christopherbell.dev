package dev.christopherbell.configuration.persistence.migration;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

/** Deterministic canonical hashing for source values and reconstructed relational rows. */
public final class CanonicalMigrationHasher {
  private static final String INVALID = "PostgreSQL migration canonical value is invalid.";
  private static final int MAX_DEPTH = 64;

  private CanonicalMigrationHasher() {}

  /** Returns a lower-case SHA-256 over one bounded canonical JSON-compatible value. */
  public static String sha256(Object value) {
    var canonical = new StringBuilder();
    append(value, canonical, new IdentityHashMap<>(), 0);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable.", impossible);
    }
  }

  private static void append(
      Object value, StringBuilder target, IdentityHashMap<Object, Boolean> ancestors, int depth) {
    if (depth > MAX_DEPTH) {
      throw invalid();
    }
    if (value == null) {
      target.append("null");
    } else if (value instanceof String text) {
      appendString(text, target);
    } else if (value instanceof Character character) {
      appendString(character.toString(), target);
    } else if (value instanceof Boolean flag) {
      target.append(flag);
    } else if (value instanceof Number number) {
      target.append(canonicalNumber(number));
    } else if (value instanceof Enum<?> enumeration) {
      appendString(enumeration.name(), target);
    } else if (value instanceof Instant instant) {
      appendTagged("instant", instant.toString(), target);
    } else if (value instanceof Date date) {
      appendTagged("instant", date.toInstant().toString(), target);
    } else if (value instanceof LocalDate date) {
      appendTagged("date", date.toString(), target);
    } else if (value instanceof UUID uuid) {
      appendTagged("uuid", uuid.toString(), target);
    } else if (value instanceof ObjectId objectId) {
      appendTagged("objectId", objectId.toHexString(), target);
    } else if (value instanceof byte[] bytes) {
      appendTagged("binary", Base64.getEncoder().encodeToString(bytes), target);
    } else if (value instanceof Map<?, ?> map) {
      enter(value, ancestors);
      appendMap(map, target, ancestors, depth + 1);
      ancestors.remove(value);
    } else if (value instanceof Collection<?> collection) {
      enter(value, ancestors);
      appendCollection(collection, target, ancestors, depth + 1);
      ancestors.remove(value);
    } else if (value.getClass().isArray()) {
      enter(value, ancestors);
      appendArray(value, target, ancestors, depth + 1);
      ancestors.remove(value);
    } else {
      throw invalid();
    }
  }

  private static void appendMap(
      Map<?, ?> map,
      StringBuilder target,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth) {
    if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
      throw invalid();
    }
    target.append('{');
    var entries = map.entrySet().stream()
        .sorted(Comparator.comparing(entry -> (String) entry.getKey()))
        .toList();
    for (var index = 0; index < entries.size(); index++) {
      if (index > 0) {
        target.append(',');
      }
      var entry = entries.get(index);
      appendString((String) entry.getKey(), target);
      target.append(':');
      append(entry.getValue(), target, ancestors, depth);
    }
    target.append('}');
  }

  private static void appendCollection(
      Collection<?> values,
      StringBuilder target,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth) {
    target.append('[');
    var first = true;
    for (var value : values) {
      if (!first) {
        target.append(',');
      }
      append(value, target, ancestors, depth);
      first = false;
    }
    target.append(']');
  }

  private static void appendArray(
      Object values,
      StringBuilder target,
      IdentityHashMap<Object, Boolean> ancestors,
      int depth) {
    target.append('[');
    var length = java.lang.reflect.Array.getLength(values);
    for (var index = 0; index < length; index++) {
      if (index > 0) {
        target.append(',');
      }
      append(java.lang.reflect.Array.get(values, index), target, ancestors, depth);
    }
    target.append(']');
  }

  private static String canonicalNumber(Number value) {
    final BigDecimal decimal;
    try {
      if (value instanceof Decimal128 decimal128) {
        decimal = decimal128.bigDecimalValue();
      } else if (value instanceof BigDecimal bigDecimal) {
        decimal = bigDecimal;
      } else if (value instanceof BigInteger bigInteger) {
        decimal = new BigDecimal(bigInteger);
      } else if (value instanceof Byte || value instanceof Short
          || value instanceof Integer || value instanceof Long) {
        decimal = BigDecimal.valueOf(value.longValue());
      } else if (value instanceof Float || value instanceof Double) {
        var floating = value.doubleValue();
        if (!Double.isFinite(floating)) {
          throw invalid();
        }
        decimal = BigDecimal.valueOf(floating);
      } else {
        decimal = new BigDecimal(value.toString());
      }
      var normalized = decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
      return normalized.toPlainString();
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalid();
    }
  }

  private static void appendTagged(String tag, String value, StringBuilder target) {
    target.append('{');
    appendString("$" + tag, target);
    target.append(':');
    appendString(value, target);
    target.append('}');
  }

  private static void appendString(String value, StringBuilder target) {
    target.append('"');
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      switch (character) {
        case '"' -> target.append("\\\"");
        case '\\' -> target.append("\\\\");
        case '\b' -> target.append("\\b");
        case '\f' -> target.append("\\f");
        case '\n' -> target.append("\\n");
        case '\r' -> target.append("\\r");
        case '\t' -> target.append("\\t");
        default -> {
          if (Character.isHighSurrogate(character)
              && index + 1 < value.length()
              && Character.isLowSurrogate(value.charAt(index + 1))) {
            target.append(character).append(value.charAt(++index));
          } else if (character < 0x20 || Character.isSurrogate(character)) {
            target.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) character));
          } else {
            target.append(character);
          }
        }
      }
    }
    target.append('"');
  }

  private static void enter(Object value, IdentityHashMap<Object, Boolean> ancestors) {
    if (ancestors.put(value, Boolean.TRUE) != null) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(INVALID);
  }
}
