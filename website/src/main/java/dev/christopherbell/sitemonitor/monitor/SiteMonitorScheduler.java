package dev.christopherbell.sitemonitor.monitor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Uses the existing application scheduling gate and the service's durable global lease. */
@Component
public class SiteMonitorScheduler {
  private final SiteMonitorService service;
  public SiteMonitorScheduler(SiteMonitorService service) { this.service = service; }
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void checkDueSite() { service.checkNextDueSite(); }
}
