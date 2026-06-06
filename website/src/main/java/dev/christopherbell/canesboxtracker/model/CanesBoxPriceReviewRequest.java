package dev.christopherbell.canesboxtracker.model;

/**
 * Admin note captured when a provisional Box Index datapoint is reviewed.
 *
 * @param note why the datapoint was approved or rejected
 */
public record CanesBoxPriceReviewRequest(String note) {}
