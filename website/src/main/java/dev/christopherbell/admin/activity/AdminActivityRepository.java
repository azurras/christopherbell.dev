package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import java.util.List;
import java.util.Optional;

public interface AdminActivityRepository {
  AdminActivity insert(AdminActivity activity);
  AdminActivity save(AdminActivity activity);
  Optional<AdminActivity> findById(String id);
  List<AdminActivity> findTop25ByOrderByCreatedOnDesc();
}
