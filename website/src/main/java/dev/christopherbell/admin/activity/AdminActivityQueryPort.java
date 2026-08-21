package dev.christopherbell.admin.activity;

import dev.christopherbell.libs.api.exception.InvalidRequestException;

/** Persistence-neutral stable admin activity page query. */
public interface AdminActivityQueryPort {
  AdminActivityPage query(AdminActivityQuery request) throws InvalidRequestException;
}
