package dev.christopherbell.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.Account;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class MongoAccountLoginStoreTest {

  @Test
  void completeLoginConditionsOnActiveObservedCredentialAndUpdatesOnlyLoginFields() {
    var mongo = mock(MongoTemplate.class);
    var store = new MongoAccountLoginStore(mongo);
    var observed = Account.builder()
        .id("account-1")
        .passwordHash("old-hash")
        .passwordSalt("old-salt")
        .role(Role.ADMIN)
        .status(AccountStatus.ACTIVE)
        .build();
    var current = Account.builder()
        .id("account-1")
        .passwordHash("new-hash")
        .role(Role.USER)
        .status(AccountStatus.ACTIVE)
        .build();
    var loginOn = Instant.parse("2026-07-29T14:00:00Z");
    when(mongo.findAndModify(
        any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Account.class)))
        .thenReturn(current);

    var result = store.completeLogin(observed, "new-hash", loginOn);

    assertThat(result).containsSame(current);
    var query = ArgumentCaptor.forClass(Query.class);
    var update = ArgumentCaptor.forClass(Update.class);
    verify(mongo).findAndModify(
        query.capture(), update.capture(), any(FindAndModifyOptions.class), eq(Account.class));
    assertThat(query.getValue().getQueryObject().toString())
        .contains("_id", "account-1", "status", "ACTIVE", "passwordHash", "old-hash",
            "passwordSalt", "old-salt")
        .doesNotContain("role");
    assertThat(update.getValue().getUpdateObject().toString())
        .contains("lastLoginOn", loginOn.toString(), "passwordHash", "new-hash", "passwordSalt")
        .doesNotContain("role", "status", "permissions");
  }
}
