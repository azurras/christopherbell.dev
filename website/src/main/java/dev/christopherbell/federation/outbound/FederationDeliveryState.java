package dev.christopherbell.federation.outbound;

/** Durable lifecycle for one controlled-peer Create delivery. */
enum FederationDeliveryState {
  PENDING,
  CLAIMED,
  RETRY,
  SUCCEEDED,
  DEAD,
  CANCELLED
}
