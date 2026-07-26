package dev.christopherbell.account.deletion;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Persistence for pseudonymous account-deletion checkpoints. */
public interface AccountDeletionJobRepository
    extends MongoRepository<AccountDeletionJob, String> {}
