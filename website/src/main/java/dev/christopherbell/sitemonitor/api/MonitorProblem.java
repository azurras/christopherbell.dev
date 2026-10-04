package dev.christopherbell.sitemonitor.api;

/** Safe pilot rejection with explicit HTTP semantics and optional retry guidance. */
public final class MonitorProblem extends RuntimeException {
  private final int status;
  public MonitorProblem(int status, String message) { super(message); this.status = status; }
  public int status() { return status; }
}
