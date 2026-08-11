package dev.christopherbell.configuration.mongo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import dev.christopherbell.account.model.Account;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class KindScopedRepositorySupportTest {
  @Test
  void sharedDeleteMechanicsMapLogicalIdsAndScopeEveryWriteToTheExactKind() {
    var mongo = mock(MongoTemplate.class);
    var repository = new TestAccountRepository(DomainMongoOperationsTestFactory.create(mongo));
    when(mongo.remove(any(Query.class), eq(Document.class), eq("accounts")))
        .thenReturn(DeleteResult.acknowledged(1));

    repository.removeId("account-1");
    repository.deleteAll(List.of(
        Account.builder().id("account-2").build(),
        Account.builder().id("account-3").build()));

    var queries = ArgumentCaptor.forClass(Query.class);
    verify(mongo, times(2)).remove(queries.capture(), eq(Document.class), eq("accounts"));
    assertThat(queries.getAllValues())
        .extracting(query -> query.getQueryObject().toString())
        .allSatisfy(query -> assertThat(query).contains("_kind=account", "_id.legacyId"))
        .anySatisfy(query -> assertThat(query).contains("account-1"))
        .anySatisfy(query -> assertThat(query).contains("account-2", "account-3"));
  }

  private static final class TestAccountRepository extends KindScopedRepositorySupport<Account> {
    private TestAccountRepository(DomainMongoOperationsFactory factory) {
      super(factory, Account.class);
    }

    private void removeId(String id) {
      super.deleteById(id);
    }

    private void deleteAll(List<Account> accounts) {
      deleteAllValues(accounts, Account::getId);
    }
  }
}
