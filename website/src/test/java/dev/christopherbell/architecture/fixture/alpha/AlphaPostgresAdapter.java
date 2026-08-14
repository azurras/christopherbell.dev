package dev.christopherbell.architecture.fixture.alpha;

import dev.christopherbell.architecture.fixture.beta.api.BetaApiContract;
import dev.christopherbell.architecture.fixture.beta.internal.BetaInternalDependency;
import dev.christopherbell.configuration.persistence.PostgresPersistence;

@PostgresPersistence
public final class AlphaPostgresAdapter {
  private final BetaApiContract published;
  private final BetaInternalDependency internal;

  public AlphaPostgresAdapter(
      BetaApiContract published,
      BetaInternalDependency internal) {
    this.published = published;
    this.internal = internal;
  }

  public BetaApiContract published() {
    return published;
  }

  public BetaInternalDependency internal() {
    return internal;
  }
}
