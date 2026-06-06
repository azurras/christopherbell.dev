package dev.christopherbell.canesboxtracker.model;

import java.util.List;

/**
 * Public Raising Canes Box Index history response.
 *
 * @param latest latest available weekly snapshot
 * @param weeks week-by-week snapshots sorted oldest to newest
 */
public record CanesBoxTrackerHistory(
    CanesBoxWeeklyPriceDetail latest,
    List<CanesBoxWeeklyPriceDetail> weeks
) {}
