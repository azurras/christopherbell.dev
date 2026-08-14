package dev.christopherbell.configuration.postgresql;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Identifies the real-engine contract methods that substantively exercise persistence ports. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface Task5ContractPorts {
  Class<?>[] value();
}
