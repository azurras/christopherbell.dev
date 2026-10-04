package dev.christopherbell.sitemonitor.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/** A single durable scheduler throttle shared by all application instances. */
public record MonitorSchedule(@Id String id, @Version Long version, Instant nextCheckOn) {}
