package dev.christopherbell.sharedfolder.media;

import java.util.List;

/** Durable media lifecycle shared by the website, browser, and isolated worker. */
public enum MediaJobStatus {
  QUEUED,
  INSPECTING,
  TRANSCODING,
  BUFFERING,
  READY,
  FAILED,
  CANCELED,
  INSUFFICIENT_SPACE,
  TIMED_OUT;

  private static final List<MediaJobStatus> ACTIVE =
      List.of(QUEUED, INSPECTING, TRANSCODING, BUFFERING);
  private static final List<MediaJobStatus> PROCESSING =
      List.of(INSPECTING, TRANSCODING, BUFFERING);

  public boolean terminal() {
    return !ACTIVE.contains(this);
  }

  public static List<MediaJobStatus> active() { return ACTIVE; }

  public static List<MediaJobStatus> processing() { return PROCESSING; }
}
