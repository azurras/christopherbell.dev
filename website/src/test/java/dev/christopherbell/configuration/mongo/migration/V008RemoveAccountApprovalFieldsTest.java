package dev.christopherbell.configuration.mongo.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;

@ExtendWith(MockitoExtension.class)
class V008RemoveAccountApprovalFieldsTest {
  @Mock private MongoTemplate mongo;

  @Test
  void identityAndChecksumAreImmutableReviewedValues() {
    var migration = new V008RemoveAccountApprovalFields();

    assertThat(migration.id()).isEqualTo("008-remove-account-approval-fields");
    assertThat(migration.checksum()).hasSize(64);
    assertThat(migration.description()).isNotBlank();
  }

  @Test
  void applyUnsetsBothRetiredFieldsFromEveryAccount() {
    new V008RemoveAccountApprovalFields().apply(mongo);

    var update = ArgumentCaptor.forClass(UpdateDefinition.class);
    verify(mongo).updateMulti(
        org.mockito.ArgumentMatchers.any(Query.class), update.capture(), eq("accounts"));
    var unset = (org.bson.Document) update.getValue().getUpdateObject().get("$unset");
    assertThat(unset.keySet()).containsExactlyInAnyOrder("isApproved", "approvedBy");
  }
}
