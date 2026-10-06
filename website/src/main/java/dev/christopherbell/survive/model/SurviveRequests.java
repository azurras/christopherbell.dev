package dev.christopherbell.survive.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request contracts accept commands and identity labels, never client-supplied game state. */
public final class SurviveRequests {
  private SurviveRequests() {}

  public record Join(@NotBlank @Size(max = 32) String name) {}
  public record Act(@NotNull SurviveAction action, @NotNull @Min(0) Long revision) {}
}
