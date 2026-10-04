package dev.christopherbell.sitemonitor.fetch;

/** Categorized incomplete-check evidence; causes remain available to diagnostics. */
public final class MonitorFetchException extends RuntimeException {
  private final String category;
  public MonitorFetchException(String category) { this(category, null); }
  public MonitorFetchException(String category, Throwable cause) {
    super("Website check could not complete: " + category, cause);
    this.category = category;
  }
  public String category() { return category; }
}
