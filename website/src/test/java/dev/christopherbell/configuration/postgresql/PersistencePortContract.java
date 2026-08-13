package dev.christopherbell.configuration.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared CRUD contract shape used by persistence-adapter parity tests. */
public interface PersistencePortContract<T> {
  T createFixture();

  T save(T value);

  java.util.Optional<T> findById(String id);

  void deleteById(String id);

  String identityOf(T value);

  default void verifyCrudRoundTrip() {
    var expected = createFixture();
    var saved = save(expected);
    assertThat(findById(identityOf(saved))).contains(saved);
    deleteById(identityOf(saved));
    assertThat(findById(identityOf(saved))).isEmpty();
  }
}
