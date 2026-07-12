package dev.christopherbell.admin.commandcenter.metrics;

import java.util.OptionalDouble;

/** Reads the Windows CPU temperature without exposing native sensor details. */
interface CpuTemperatureSensorClient extends AutoCloseable {
  OptionalDouble readCelsius();

  @Override
  default void close() {}
}
