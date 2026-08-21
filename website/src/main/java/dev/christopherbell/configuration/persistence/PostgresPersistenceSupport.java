package dev.christopherbell.configuration.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a non-bean mapper or helper contained inside a PostgreSQL persistence boundary. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PostgresPersistenceSupport {}
