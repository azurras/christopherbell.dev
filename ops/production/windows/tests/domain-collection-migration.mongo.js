"use strict";

const manifest = DomainCollectionManifest.MANIFEST;
const migration = DomainCollectionMigration;
const digest = DomainCollectionManifest.DIGEST;
const owner = "0123456789abcdef0123456789abcdef";
const wrongOwner = "fedcba9876543210fedcba9876543210";
const release = "a".repeat(40);
const databases = Object.freeze({
  main: "cbell_candidate_aaaaaaaaaaaa_aaaaaaaaaaaaaaaaaaaaaaaa",
  restore: "cbell_candidate_bbbbbbbbbbbb_bbbbbbbbbbbbbbbbbbbbbbbb",
  malformed: "cbell_candidate_cccccccccccc_cccccccccccccccccccccccc",
  stale: "cbell_candidate_dddddddddddd_dddddddddddddddddddddddd",
  collision: "cbell_candidate_eeeeeeeeeeee_eeeeeeeeeeeeeeeeeeeeeeee",
  unexpected: "cbell_candidate_ffffffffffff_ffffffffffffffffffffffff",
  optional: "cbell_candidate_111111111111_111111111111111111111111",
  malformedStage: "cbell_candidate_222222222222_222222222222222222222222"
});

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function command(database, action, commandOwner = owner) {
  const outcome = migration.execute(db, [database, action, digest, commandOwner, release]);
  assert(outcome.database === database, action + " omitted its database");
  assert(outcome.action === action, action + " omitted its action");
  assert(outcome.manifestDigest === digest, action + " omitted its manifest digest");
  assert(outcome.kinds.length === manifest.kinds.length, action + " omitted kind evidence");
  assert(outcome.indexes.length === manifest.targets.length, action + " omitted index evidence");
  return outcome;
}

function seed(databaseName) {
  const database = db.getSiblingDB(databaseName);
  database.dropDatabase();
  for (const kind of manifest.kinds) {
    if (!kind.source) continue;
    let id = kind.sourceId || kind.kind + "-id";
    if (kind.kind === "account") id = ObjectId("64b64b64b64b64b64b64b64b");
    if (kind.kind === "migration_record") id = "014-consolidate-music-runtime-state";
    const document = {
      _id: id,
      marker: kind.kind,
      longValue: Long.fromString("9007199254740993"),
      decimalValue: Decimal128.fromString("1234567890.0123456789"),
      recordedAt: ISODate("2026-08-11T00:00:00.000Z"),
      nested: { z: 2, a: 1 }
    };
    if (kind.kind === "migration_record") document.status = "APPLIED";
    database.getCollection(kind.source).insertOne(document);
  }
  for (const name of manifest.dropOnly) {
    database.getCollection(name).insertOne({ _id: name, retained: true });
  }
  return database;
}

function stageAll(databaseName) {
  let outcome;
  let prior = 0;
  for (let count = 0; count < manifest.kinds.length + manifest.targets.length; count++) {
    outcome = command(databaseName, "stage");
    const ledger = db.getSiblingDB(databaseName)
      .getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.stageIndex === prior + 1, "staging advanced by more than one operation");
    prior = ledger.payload.stageIndex;
  }
  assert(outcome.complete && outcome.state === "STAGED", "staging did not complete exactly");
  const revision = db.getSiblingDB(databaseName)
    .getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.revision;
  assert(command(databaseName, "stage").complete, "completed staging retry was not idempotent");
  assert(db.getSiblingDB(databaseName).getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
  "completed staging retry mutated the ledger");
}

function rawRename(databaseName, from, to) {
  const outcome = db.getSiblingDB("admin").runCommand({
    renameCollection: databaseName + "." + from,
    to: databaseName + "." + to,
    dropTarget: false
  });
  assert(outcome.ok === 1, "crash-boundary rename simulation failed");
}

function publishAll(databaseName) {
  let prior = 0;
  while (true) {
    const outcome = command(databaseName, "publish-next");
    const database = db.getSiblingDB(databaseName);
    const ledger = database.getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" })
      || database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.publishIndex === prior + 1, "publication advanced by more than one rename");
    prior = ledger.payload.publishIndex;
    if (outcome.complete) {
      const revision = ledger.payload.revision;
      assert(command(databaseName, "publish-next").complete,
        "completed publication retry was not idempotent");
      assert(database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
      "completed publication retry mutated the ledger");
      return prior;
    }
  }
}

function reverseAll(databaseName) {
  const database = db.getSiblingDB(databaseName);
  let ledger = database.getCollection("__domain_stage__application_migrations")
    .findOne({ _kind: "domain_collection_cutover" })
    || database.getCollection("application_migrations")
      .findOne({ _kind: "domain_collection_cutover" });
  let prior = ledger.payload.publishIndex;
  if (prior > 0) {
    const forward = ledger.payload.publicationOperations[prior - 1];
    rawRename(databaseName, forward.to, forward.from);
  }
  while (true) {
    const outcome = command(databaseName, "reverse-next");
    ledger = database.getCollection("__domain_stage__application_migrations")
      .findOne({ _kind: "domain_collection_cutover" })
      || database.getCollection("application_migrations")
        .findOne({ _kind: "domain_collection_cutover" });
    assert(ledger.payload.publishIndex === prior - 1,
      "reversal advanced by more than one operation");
    prior = ledger.payload.publishIndex;
    if (outcome.complete) {
      const revision = ledger.payload.revision;
      assert(command(databaseName, "reverse-next").complete,
        "completed reversal retry was not idempotent");
      assert(database.getCollection("__domain_stage__application_migrations")
        .findOne({ _kind: "domain_collection_cutover" }).payload.revision === revision,
      "completed reversal retry mutated the ledger");
      return;
    }
  }
}

function expectFailure(work, label) {
  let failed = false;
  try { work(); } catch (expected) { failed = true; }
  assert(failed, label + " did not fail closed");
}

for (const name of Object.values(databases)) db.getSiblingDB(name).dropDatabase();

const restore = seed(databases.restore);
assert(command(databases.restore, "restore-verify").state === "LEGACY_RESTORE_VERIFIED",
  "restore verification failed");

const optional = seed(databases.optional);
optional.getCollection("scheduled_collector_runs").drop();
assert(command(databases.optional, "preview").kinds
  .find((kind) => kind.kind === "scheduled_collector_run").count === 0,
"absent optional source was not reported as empty");
stageAll(databases.optional);
command(databases.optional, "verify-stage");

const malformed = seed(databases.malformed);
malformed.getCollection("accounts").deleteMany({});
malformed.getCollection("accounts").insertOne({ _id: { nested: "unsupported" }, email: "x" });
expectFailure(() => command(databases.malformed, "preview"), "malformed source identity");

const stale = seed(databases.stale);
stale.createCollection("__domain_stage__accounts");
expectFailure(() => command(databases.stale, "stage"), "stale target residue");

const unexpected = seed(databases.unexpected);
unexpected.createCollection("unapproved_collection");
expectFailure(() => command(databases.unexpected, "preview"), "unexpected collection");

const collision = seed(databases.collision);
command(databases.collision, "stage");
expectFailure(() => command(databases.collision, "stage", wrongOwner), "CAS owner mismatch");
const follow = collision.getCollection("account_follows").findOne({});
collision.getCollection("__domain_stage__accounts").insertOne({
  _id: { kind: "account_follow", legacyId: follow._id },
  _kind: "account_follow",
  schemaVersion: 1,
  payload: { corrupted: true }
});
expectFailure(() => command(databases.collision, "stage"), "staging collision");

const main = seed(databases.main);
const preview = command(databases.main, "preview");
assert(preview.kinds.length === 52, "preview did not report all kinds");
stageAll(databases.main);
const account = main.getCollection("__domain_stage__accounts")
  .findOne({ _kind: "account" });
assert(account._id.legacyId instanceof ObjectId, "ObjectId identity type changed");
assert(account.payload.longValue instanceof Long, "int64 payload type changed");
assert(account.payload.decimalValue instanceof Decimal128, "decimal payload type changed");
assert(account.payload.recordedAt instanceof Date, "date payload type changed");
const stageVerification = command(databases.main, "verify-stage");
assert(stageVerification.indexes.reduce((sum, target) => sum + target.count, 0) === 126,
  "stage index count is not exact");
let verifiedLedger = main.getCollection("__domain_stage__application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(command(databases.main, "verify-stage").complete,
  "completed stage verification retry was not idempotent");
assert(main.getCollection("__domain_stage__application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === verifiedLedger.payload.revision,
"completed stage verification retry mutated the ledger");

const malformedStage = seed(databases.malformedStage);
stageAll(databases.malformedStage);
const malformedEnvelope = malformedStage.getCollection("__domain_stage__accounts")
  .findOne({ _kind: "account" });
malformedStage.getCollection("__domain_stage__accounts")
  .updateOne({ _id: malformedEnvelope._id }, { $set: { schemaVersion: "1" } });
expectFailure(() => command(databases.malformedStage, "verify-stage"), "malformed envelope");
malformedStage.getCollection("__domain_stage__accounts")
  .updateOne({ _id: malformedEnvelope._id }, { $set: { schemaVersion: 1 } });
malformedStage.getCollection("__domain_stage__accounts").createIndex({ unexpected: 1 });
expectFailure(() => command(databases.malformedStage, "verify-stage"), "unexpected target index");
malformedStage.getCollection("__domain_stage__accounts").dropIndex("unexpected_1");
command(databases.malformedStage, "verify-stage");

let crashLedger = main.getCollection("__domain_stage__application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
rawRename(databases.main, crashLedger.payload.publicationOperations[0].from,
  crashLedger.payload.publicationOperations[0].to);
assert(command(databases.main, "publish-next").state === "PUBLISHING",
  "publication did not resume after a completed rename");
for (let count = 0; count < 6; count++) command(databases.main, "publish-next");
reverseAll(databases.main);
command(databases.main, "verify-stage");
publishAll(databases.main);
command(databases.main, "verify-live");
reverseAll(databases.main);
command(databases.main, "verify-stage");
publishAll(databases.main);
const live = command(databases.main, "verify-live");
assert(live.state === "TARGET_ACTIVE", "live verification did not activate target");
verifiedLedger = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(command(databases.main, "verify-live").complete,
  "completed live verification retry was not idempotent");
assert(main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === verifiedLedger.payload.revision,
"completed live verification retry mutated the ledger");

let drops = 0;
while (true) {
  const outcome = command(databases.main, "drop-legacy");
  drops += 1;
  const progress = main.getCollection("application_migrations")
    .findOne({ _kind: "domain_collection_cutover" }).payload.dropIndex;
  assert(progress === drops, "legacy deletion advanced by more than one operation");
  if (outcome.complete) break;
}
assert(drops > 0, "legacy deletion performed no allowlisted steps");
const dropRevision = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision;
assert(command(databases.main, "drop-legacy").complete,
  "completed legacy deletion retry was not idempotent");
assert(main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" }).payload.revision === dropRevision,
"completed legacy deletion retry mutated the ledger");
const names = main.getCollectionInfos().map((info) => info.name)
  .filter((name) => !name.startsWith("system.")).sort();
assert(JSON.stringify(names) === JSON.stringify([...manifest.targets].sort()),
  "final collection catalog is not exact");
const indexCount = manifest.targets.reduce((sum, target) =>
  sum + main.getCollection(target).getIndexes().length, 0);
assert(indexCount === 126, "final index catalog is not exact");
const ledger = main.getCollection("application_migrations")
  .findOne({ _kind: "domain_collection_cutover" });
assert(ledger.payload.state === "TARGET_ACTIVE" && ledger.payload.completed === true
  && ledger.payload.legacyDropped === true && ledger.payload.manifestDigest === digest,
"final cutover ledger is not startup-safe");
expectFailure(() => command(databases.main, "reverse-next"), "post-deletion reversal");

print(JSON.stringify({ complete: true, databases: Object.keys(databases).length,
  kinds: 52, indexes: 126, collections: names.length, drops }));
