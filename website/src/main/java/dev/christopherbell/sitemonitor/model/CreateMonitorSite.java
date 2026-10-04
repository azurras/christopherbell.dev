package dev.christopherbell.sitemonitor.model;

import jakarta.validation.constraints.Size;
import java.util.List;

/** Input bounds apply before parsing, DNS resolution and persistence. */
public record CreateMonitorSite(@Size(max = 80) String label, @Size(max = 300) String origin,
    @Size(max = 5) List<@Size(max = 300) String> paths, boolean demo) {}
