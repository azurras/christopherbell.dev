package dev.christopherbell.configuration.mongo.domain;

import java.time.Duration;
import java.util.Objects;
import org.springframework.data.mongodb.core.query.Update;

/** Safe description of one Mongo lease mutation whose deadline is issued by the database. */
public final class MongoDatabaseLeaseMutation {
  enum DeadlineExpectation {
    UNEXPIRED,
    EXPIRED_OR_MISSING,
    EXPIRED_OR_SAME_OWNER
  }

  private final Update update;
  private final String deadlineField;
  private final Duration duration;
  private final DeadlineExpectation expectation;
  private final String sameOwnerField;
  private final Object sameOwnerValue;
  private final boolean upsert;
  private final boolean advanceVersion;

  private MongoDatabaseLeaseMutation(
      Update update,
      String deadlineField,
      Duration duration,
      DeadlineExpectation expectation,
      String sameOwnerField,
      Object sameOwnerValue,
      boolean upsert,
      boolean advanceVersion) {
    this.update = Objects.requireNonNull(update, "update");
    this.deadlineField = requireField(deadlineField);
    this.duration = duration == null ? null : requireDuration(duration);
    this.expectation = Objects.requireNonNull(expectation, "expectation");
    this.sameOwnerField = sameOwnerField;
    this.sameOwnerValue = sameOwnerValue;
    this.upsert = upsert;
    this.advanceVersion = advanceVersion;
  }

  public static MongoDatabaseLeaseMutation acquire(
      Update update,
      String deadlineField,
      Duration duration,
      String ownerField,
      Object ownerValue) {
    return new MongoDatabaseLeaseMutation(update, deadlineField, duration,
        DeadlineExpectation.EXPIRED_OR_SAME_OWNER, requireField(ownerField),
        Objects.requireNonNull(ownerValue, "ownerValue"), true, false);
  }

  public static MongoDatabaseLeaseMutation renew(
      Update update, String deadlineField, Duration duration, boolean advanceVersion) {
    return new MongoDatabaseLeaseMutation(update, deadlineField, duration,
        DeadlineExpectation.UNEXPIRED, null, null, false, advanceVersion);
  }

  public static MongoDatabaseLeaseMutation claimExpired(
      Update update, String deadlineField, Duration duration, boolean advanceVersion) {
    return new MongoDatabaseLeaseMutation(update, deadlineField, duration,
        DeadlineExpectation.EXPIRED_OR_MISSING, null, null, false, advanceVersion);
  }

  public static MongoDatabaseLeaseMutation release(
      Update update, String deadlineField, boolean advanceVersion) {
    return new MongoDatabaseLeaseMutation(update, deadlineField, null,
        DeadlineExpectation.UNEXPIRED, null, null, false, advanceVersion);
  }

  Update update() { return update; }
  String deadlineField() { return deadlineField; }
  Duration duration() { return duration; }
  DeadlineExpectation expectation() { return expectation; }
  String sameOwnerField() { return sameOwnerField; }
  Object sameOwnerValue() { return sameOwnerValue; }
  boolean upsert() { return upsert; }
  boolean advanceVersion() { return advanceVersion; }

  private static String requireField(String field) {
    if (field == null || field.isBlank()) {
      throw new IllegalArgumentException("Lease field is required.");
    }
    return field;
  }

  private static Duration requireDuration(Duration duration) {
    if (duration.isZero() || duration.isNegative() || duration.toMillis() <= 0) {
      throw new IllegalArgumentException("Lease duration must be at least one millisecond.");
    }
    return duration;
  }
}
