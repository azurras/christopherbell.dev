package dev.christopherbell.survive.model;

/** Desired transient pose; private ownership is resolved from the request, never this payload. */
public record SurviveMovement(double x, double z, double heading, long sequence) {
  public SurviveMovement {
    if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(heading)
        || Math.hypot(x, z) > 78 || Math.abs(heading) > Math.PI || sequence < 0) {
      throw new IllegalArgumentException("Movement must be finite, on the island, with a nonnegative sequence.");
    }
  }
}
