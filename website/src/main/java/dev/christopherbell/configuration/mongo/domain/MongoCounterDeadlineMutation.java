package dev.christopherbell.configuration.mongo.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Domain field names and durations for one atomic signed-counter/deadline mutation. */
public record MongoCounterDeadlineMutation(
    String counterField, int delta, List<String> deadlineCounterFields,
    String createdOnField, String deadlineField, String updatedOnField,
    String extendedOnField, Instant changedOn, Duration baseLifespan,
    Duration extensionPerCount, boolean recordExtension, boolean deriveDeadline) {
  public MongoCounterDeadlineMutation {
    deadlineCounterFields = List.copyOf(deadlineCounterFields);
    if (deadlineCounterFields.isEmpty() || changedOn == null || baseLifespan.isNegative()
        || extensionPerCount.isNegative() || !deadlineCounterFields.contains(counterField)) {
      throw new IllegalArgumentException("Counter deadline mutation is invalid.");
    }
  }
}
