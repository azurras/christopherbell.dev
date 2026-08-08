package dev.christopherbell.architecture.fixture.alpha;

import dev.christopherbell.architecture.fixture.beta.api.BetaApiContract;
import dev.christopherbell.architecture.fixture.beta.api.internal.Secret;
import dev.christopherbell.architecture.fixture.beta.internal.BetaInternalDependency;
import dev.christopherbell.architecture.fixture.ops.api.OrchestrationDependency;

public final class AlphaConsumer {
  private final BetaApiContract apiContract;
  private final Secret nestedApiInternalDependency;
  private final BetaInternalDependency internalDependency;
  private final OrchestrationDependency orchestrationDependency;

  public AlphaConsumer(
      BetaApiContract apiContract,
      Secret nestedApiInternalDependency,
      BetaInternalDependency internalDependency,
      OrchestrationDependency orchestrationDependency) {
    this.apiContract = apiContract;
    this.nestedApiInternalDependency = nestedApiInternalDependency;
    this.internalDependency = internalDependency;
    this.orchestrationDependency = orchestrationDependency;
  }
}
