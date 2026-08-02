package dev.christopherbell.vehicle.nhtsa.decode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class VinDecodeBulkheadTest {
  @Test
  void defaultBoundaryRejectsTheNinthConcurrentRemoteCall() {
    var bulkhead = new VinDecodeBulkhead();
    var permits = new ArrayList<VinDecodeBulkhead.Permit>();

    for (var index = 0; index < 8; index++) {
      permits.add(bulkhead.tryAcquire().orElseThrow());
    }

    assertFalse(bulkhead.tryAcquire().isPresent());
    permits.forEach(VinDecodeBulkhead.Permit::close);
  }

  @Test
  void closingAPermitMakesCapacityAvailableAndIsIdempotent() {
    var bulkhead = new VinDecodeBulkhead(1);
    var first = bulkhead.tryAcquire().orElseThrow();

    assertFalse(bulkhead.tryAcquire().isPresent());
    first.close();
    first.close();

    var second = bulkhead.tryAcquire();
    assertTrue(second.isPresent());
    assertFalse(bulkhead.tryAcquire().isPresent());
    second.orElseThrow().close();
  }
}
