package dev.christopherbell.sharedfolder.model;

/** Untrusted client duration observation for one exact station sequence and track. */
public record SharedFolderRadioDurationRequest(
    long stationSequence,
    String path,
    double durationSeconds) {
  public static final double MIN_DURATION_SECONDS = 1;
  public static final double MAX_DURATION_SECONDS = 86_400;

  /** Reports whether a duration is finite and within the accepted inclusive bounds. */
  public static boolean isValidDuration(double durationSeconds) {
    return Double.isFinite(durationSeconds)
        && durationSeconds >= MIN_DURATION_SECONDS
        && durationSeconds <= MAX_DURATION_SECONDS;
  }
}
