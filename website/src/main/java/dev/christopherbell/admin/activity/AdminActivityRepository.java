package dev.christopherbell.admin.activity;

import dev.christopherbell.admin.model.AdminActivity;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminActivityRepository extends MongoRepository<AdminActivity, String> {
  List<AdminActivity> findTop25ByOrderByCreatedOnDesc();
}
