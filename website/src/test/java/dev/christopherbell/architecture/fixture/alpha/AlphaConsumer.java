package dev.christopherbell.architecture.fixture.alpha;

import dev.christopherbell.architecture.fixture.beta.api.BetaApiContract;
import dev.christopherbell.architecture.fixture.beta.internal.BetaInternalDependency;
import dev.christopherbell.architecture.fixture.ops.OrchestrationDependency;

public final class AlphaConsumer {
  private final BetaApiContract apiContract;
  private final BetaInternalDependency internalDependency;
  private final OrchestrationDependency orchestrationDependency;

  public AlphaConsumer(
      BetaApiContract apiContract,
      BetaInternalDependency internalDependency,
      OrchestrationDependency orchestrationDependency) {
    this.apiContract = apiContract;
    this.internalDependency = internalDependency;
    this.orchestrationDependency = orchestrationDependency;
  }
}
