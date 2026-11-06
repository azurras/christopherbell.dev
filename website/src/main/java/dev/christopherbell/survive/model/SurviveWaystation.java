package dev.christopherbell.survive.model;

/** The North Gate assignment contact and the per-survivor stages of its field patrol. */
public final class SurviveWaystation {
  public static final Keeper KEEPER = new Keeper("waystation-keeper", "Waystation Keeper", 2, -35, 4);

  private SurviveWaystation() {}

  /** Public placement and interaction range in metres. */
  public record Keeper(String id, String name, double x, double z, double talkRange) {}

  public enum Assignment { AVAILABLE, ACTIVE, RETURN, COMPLETE }
}
