package dev.christopherbell.libs.mongo.lease;

/**
 * Mongo document identity retained so the released collection manifest remains stable while the
 * runtime scheduler uses its engine-neutral value type.
 */
@Deprecated(forRemoval = false)
public final class ScheduledCollectorRun
    extends dev.christopherbell.libs.lease.ScheduledCollectorRun {

  public ScheduledCollectorRun() {
    super();
  }

  /** Creates the transition document without duplicating scheduler behavior. */
  public static ScheduledCollectorRun from(
      dev.christopherbell.libs.lease.ScheduledCollectorRun source) {
    var document = new ScheduledCollectorRun();
    document.setId(source.getId());
    document.setCollectorName(source.getCollectorName());
    document.setOwnerToken(source.getOwnerToken());
    document.setStatus(source.getStatus());
    document.setStartedOn(source.getStartedOn());
    document.setCompletedOn(source.getCompletedOn());
    document.setErrorCategory(source.getErrorCategory());
    return document;
  }

  /** Returns the engine-neutral scheduler value exposed through the runtime port. */
  public dev.christopherbell.libs.lease.ScheduledCollectorRun toDomain() {
    return dev.christopherbell.libs.lease.ScheduledCollectorRun.builder()
        .id(getId())
        .collectorName(getCollectorName())
        .ownerToken(getOwnerToken())
        .status(getStatus())
        .startedOn(getStartedOn())
        .completedOn(getCompletedOn())
        .errorCategory(getErrorCategory())
        .build();
  }
}
