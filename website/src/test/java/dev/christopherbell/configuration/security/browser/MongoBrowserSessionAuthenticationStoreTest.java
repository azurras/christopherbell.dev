package dev.christopherbell.configuration.security.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.christopherbell.account.model.AccountPermission;
import dev.christopherbell.account.model.AccountStatus;
import dev.christopherbell.account.model.Role;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;

class MongoBrowserSessionAuthenticationStoreTest {

  @Test
  void findByIdUsesOneBoundedJoinWithOnlyCurrentAccountSecurityFields() {
    var mongo = org.mockito.Mockito.mock(MongoTemplate.class);
    var expected = new BrowserSessionAuthentication(
        BrowserSession.builder().id("session-1").accountId("account-1").build(),
        new BrowserSessionAccount(
            "account-1",
            "password-hash",
            Role.USER,
            Set.of(AccountPermission.SHARED_FOLDER_READ),
            AccountStatus.ACTIVE));
    when(mongo.aggregate(
        any(Aggregation.class),
        eq("sessions"),
        eq(BrowserSessionAuthentication.class)))
        .thenReturn(new AggregationResults<>(List.of(expected), new Document()));
    var store = new MongoBrowserSessionAuthenticationStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo));

    assertThat(store.findById("session-1")).containsSame(expected);

    var aggregation = ArgumentCaptor.forClass(Aggregation.class);
    verify(mongo).aggregate(
        aggregation.capture(),
        eq("sessions"),
        eq(BrowserSessionAuthentication.class));
    assertThat(aggregation.getValue().toString())
        .contains("$match", "_kind", "browser_session", "session-1", "$limit", "1", "$lookup",
            "accounts", "account")
        .contains("passwordHash", "role", "permissions", "status")
        .doesNotContain("email", "firstName", "lastName", "username");
  }

  @Test
  void findByIdReturnsEmptyWhenTheSessionCannotJoinACurrentAccount() {
    var mongo = org.mockito.Mockito.mock(MongoTemplate.class);
    when(mongo.aggregate(
        any(Aggregation.class),
        eq("sessions"),
        eq(BrowserSessionAuthentication.class)))
        .thenReturn(new AggregationResults<>(List.of(), new Document()));

    assertThat(new MongoBrowserSessionAuthenticationStore(
        dev.christopherbell.configuration.mongo.domain.DomainMongoOperationsTestFactory.create(mongo))
        .findById("session-1"))
        .isEmpty();
  }
}
