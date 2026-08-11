"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");

const manifestModule = require("../scripts/DomainCollectionManifest.js");
const migration = require("../scripts/Invoke-DomainCollectionMigration.js");

test("manifest freezes all targets kinds sources indexes and V014 drop-only artifacts", () => {
  const manifest = manifestModule.requireDigest(
    "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24");

  assert.equal(manifest.targets.length, 14);
  assert.equal(manifest.kinds.length, 52);
  assert.equal(manifest.kinds.filter((kind) => kind.source !== null).length, 51);
  assert.equal(new Set(manifest.kinds.filter((kind) => kind.source).map((kind) => kind.source)).size, 50);
  assert.equal(manifest.indexes.length, 126);
  assert.deepEqual(manifest.dropOnly, ["music_queue_state", "music_radio_state"]);
  assert.equal(manifest.kinds.find((kind) => kind.kind === "music_runtime_state").source,
    "music_runtime_state");
});

test("command parser rejects unsafe database action digest owner and release inputs", () => {
  const valid = ["cbell_candidate_123456789abc_1234567890abcdef12345678", "preview",
    "576fa007a848780ff8f1e21e4a492f3758ad92ed72d829a75819bdfaf41a9b24",
    "0123456789abcdef0123456789abcdef", "a".repeat(40)];
  assert.equal(migration.parseCommand(valid).action, "preview");

  for (const replacement of [
    [0, "admin"], [1, "eval"], [2, "0".repeat(64)], [3, "owner value"], [4, "A".repeat(40)]
  ]) {
    const args = valid.slice();
    args[replacement[0]] = replacement[1];
    assert.throws(() => migration.parseCommand(args), /invalid/i);
  }
});

test("failure results retain the action contract without echoing unsafe arguments", () => {
  const failure = migration.redactedFailure([
    "admin", "eval", "secret-digest", "secret-owner", "secret-release"
  ]);

  assert.deepEqual(failure, {
    complete: false,
    database: null,
    action: null,
    state: "FAILED",
    manifestDigest: null,
    kinds: [],
    indexes: [],
    nextOperation: null,
    category: "DOMAIN_COLLECTION_MIGRATION_FAILED"
  });
  assert.equal(JSON.stringify(failure).includes("secret"), false);
});

test("canonical Extended JSON distinguishes BSON types and normalizes document keys", () => {
  assert.notEqual(
    migration.canonicalExtendedJson({ value: { $numberLong: "7" } }),
    migration.canonicalExtendedJson({ value: 7 }));
  assert.equal(
    migration.canonicalExtendedJson({ z: 1, a: { y: 2, x: 3 } }),
    migration.canonicalExtendedJson({ a: { x: 3, y: 2 }, z: 1 }));
});

test("publication plan performs one rename at a time and remains exactly reversible", () => {
  const manifest = manifestModule.requireDigest(manifestModule.DIGEST);
  const operations = migration.buildPublicationOperations(manifest);
  assert.equal(operations.filter((operation) => operation.kind === "rename").length,
    operations.length);
  assert.equal(new Set(operations.map((operation) => `${operation.from}->${operation.to}`)).size,
    operations.length);
  assert.deepEqual(migration.reversePublicationOperations(operations),
    operations.toReversed().map((operation) => ({
      kind: "rename", from: operation.to, to: operation.from
    })));
});
