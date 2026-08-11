"use strict";

const ManifestApi = typeof globalThis !== "undefined" && globalThis.DomainCollectionManifest
  ? globalThis.DomainCollectionManifest
  : require("./DomainCollectionManifest.js");

const ACTIONS = Object.freeze([
  "preview", "stage", "verify-stage", "publish-next", "verify-live",
  "drop-legacy", "reverse-next", "restore-verify"
]);
const DATABASE = /^(christopherbell|cbell_candidate_[0-9a-f]{12}_[0-9a-f]{24})$/;
const DIGEST_PATTERN = /^[0-9a-f]{64}$/;
const OWNER = /^[0-9a-f]{32}$/;
const RELEASE = /^[0-9a-f]{40}$/;
const BACKUP = /^[0-9a-f]{64}$/;
const LEDGER_ID = "domain-collection-cutover";
const LEDGER_KIND = "domain_collection_cutover";
const STAGE_PREFIX = "__domain_stage__";
const LEGACY_PREFIX = "__domain_legacy__";
const V014_ID = "014-consolidate-music-runtime-state";
const V014_CHECKSUM = "11a69bdd4556cfc38060ccdda5075fb9d6bc36f1cc414edd7b26cd61a74b5cbb";
const V014_DESCRIPTION = "Consolidate Music queue and radio runtime state";

function fail(message) {
  throw new Error(message);
}

function parseCommand(args) {
  if (!Array.isArray(args) || args.length !== 6) fail("Mongo migration arguments are invalid.");
  const [database, action, manifestDigest, ownerToken, release, backupIdentity] = args;
  if (typeof database !== "string" || !DATABASE.test(database)) {
    fail("Mongo migration database is invalid.");
  }
  if (typeof action !== "string" || !ACTIONS.includes(action)) {
    fail("Mongo migration action is invalid.");
  }
  if (typeof manifestDigest !== "string" || !DIGEST_PATTERN.test(manifestDigest)
      || manifestDigest !== ManifestApi.DIGEST) {
    fail("Mongo migration manifest digest is invalid.");
  }
  if (typeof ownerToken !== "string" || !OWNER.test(ownerToken)) {
    fail("Mongo migration owner is invalid.");
  }
  if (typeof release !== "string" || !RELEASE.test(release)) {
    fail("Mongo migration release is invalid.");
  }
  if (typeof backupIdentity !== "string" || !BACKUP.test(backupIdentity)) {
    fail("Mongo migration backup identity is invalid.");
  }
  return Object.freeze({ database, action, manifestDigest, ownerToken, release, backupIdentity });
}

function redactedFailure(args) {
  const values = Array.isArray(args) ? args : [];
  const database = typeof values[0] === "string" && DATABASE.test(values[0]) ? values[0] : null;
  const action = typeof values[1] === "string" && ACTIONS.includes(values[1]) ? values[1] : null;
  const manifestDigest = typeof values[2] === "string" && values[2] === ManifestApi.DIGEST
    ? values[2] : null;
  const backupIdentity = typeof values[5] === "string" && BACKUP.test(values[5])
    ? values[5] : null;
  return Object.freeze({
    complete: false,
    database,
    action,
    state: "FAILED",
    manifestDigest,
    backupIdentity,
    evidenceDigest: null,
    evidence: null,
    kinds: Object.freeze([]),
    indexes: Object.freeze([]),
    nextOperation: null,
    category: "DOMAIN_COLLECTION_MIGRATION_FAILED"
  });
}

function cryptoModule() {
  if (typeof require !== "function") fail("SHA-256 is unavailable.");
  try {
    return require("node:crypto");
  } catch (ignored) {
    return require("crypto");
  }
}

function sha256(value) {
  return cryptoModule().createHash("sha256").update(value, "utf8").digest("hex");
}

function canonicalValue(value) {
  if (Array.isArray(value)) return value.map(canonicalValue);
  if (value && typeof value === "object") {
    const result = {};
    for (const key of Object.keys(value).sort()) result[key] = canonicalValue(value[key]);
    return result;
  }
  return value;
}

function canonicalExtendedJson(value) {
  let portable = value;
  if (typeof EJSON !== "undefined" && EJSON && typeof EJSON.stringify === "function") {
    portable = JSON.parse(EJSON.stringify(value, { relaxed: false }));
  }
  return JSON.stringify(canonicalValue(portable));
}

function canonicalChecksum(documents) {
  const rows = documents.map(canonicalExtendedJson).sort();
  return sha256(rows.join("\n") + (rows.length === 0 ? "" : "\n"));
}

function evidenceDigest(evidence) {
  return sha256(canonicalExtendedJson(evidence));
}

function requireProtectedEvidence(command, evidence, expectedDigest) {
  const fields = evidence && typeof evidence === "object" && !Array.isArray(evidence)
    ? Object.keys(evidence).sort() : [];
  const expectedFields = [
    "backupIdentity", "collections", "kinds", "manifestDigest", "presentSources",
    "release", "v014", "version"
  ].sort();
  if (canonicalExtendedJson(fields) !== canonicalExtendedJson(expectedFields)
      || evidence.version !== 1
      || evidence.manifestDigest !== command.manifestDigest
      || evidence.release !== command.release
      || evidence.backupIdentity !== command.backupIdentity
      || !Array.isArray(evidence.presentSources)
      || !Array.isArray(evidence.kinds) || evidence.kinds.length !== 52
      || !Array.isArray(evidence.collections)
      || !evidence.v014 || typeof evidence.v014 !== "object" || Array.isArray(evidence.v014)
      || !DIGEST_PATTERN.test(String(expectedDigest))
      || evidenceDigest(evidence) !== expectedDigest) {
    fail("Mongo protected evidence is invalid.");
  }
  const uniqueSortedSources = [...new Set(evidence.presentSources)].sort();
  if (!sameValue(evidence.presentSources, uniqueSortedSources)
      || evidence.presentSources.some((name) => !sourceNames(ManifestApi.MANIFEST).includes(name))) {
    fail("Mongo protected evidence is invalid.");
  }
  for (const metric of evidence.kinds) {
    if (!metric || !sameValue(Object.keys(metric), ["kind", "count", "checksum"])
        || typeof metric.kind !== "string" || !Number.isSafeInteger(metric.count)
        || metric.count < 0 || !DIGEST_PATTERN.test(metric.checksum)) {
      fail("Mongo protected evidence is invalid.");
    }
  }
  for (const metric of evidence.collections) {
    if (!metric || !sameValue(Object.keys(metric), ["name", "count", "checksum", "indexDigest"])
        || typeof metric.name !== "string" || !Number.isSafeInteger(metric.count)
        || metric.count < 0 || !DIGEST_PATTERN.test(metric.checksum)
        || !DIGEST_PATTERN.test(metric.indexDigest)) {
      fail("Mongo protected evidence is invalid.");
    }
  }
  const v014Keys = ["id", "checksum", "queueChecksum", "radioChecksum", "targetChecksum"];
  if (!sameValue(Object.keys(evidence.v014), v014Keys)
      || evidence.v014.id !== V014_ID || evidence.v014.checksum !== V014_CHECKSUM
      || !DIGEST_PATTERN.test(evidence.v014.queueChecksum)
      || !DIGEST_PATTERN.test(evidence.v014.radioChecksum)
      || !DIGEST_PATTERN.test(evidence.v014.targetChecksum)) {
    fail("Mongo protected evidence is invalid.");
  }
  return evidence;
}

function sourceNames(manifest) {
  return [...new Set(manifest.kinds.filter((kind) => kind.source).map((kind) => kind.source))];
}

function orderedTargets(manifest) {
  return manifest.targets.filter((target) => target !== "application_migrations")
    .concat("application_migrations");
}

function buildPublicationOperations(manifest, presentSources) {
  const present = new Set(presentSources || sourceNames(manifest));
  const operations = [];
  for (const target of orderedTargets(manifest)) {
    if (present.has(target)) {
      operations.push(Object.freeze({ kind: "rename", from: target, to: LEGACY_PREFIX + target }));
    }
    operations.push(Object.freeze({ kind: "rename", from: STAGE_PREFIX + target, to: target }));
  }
  return Object.freeze(operations);
}

function reversePublicationOperations(operations) {
  return operations.toReversed().map((operation) => Object.freeze({
    kind: "rename", from: operation.to, to: operation.from
  }));
}

function collectionExists(database, name) {
  return database.getCollectionInfos({ name }).length === 1;
}

function applicationCollections(database) {
  return database.getCollectionInfos()
    .map((info) => info.name)
    .filter((name) => !name.startsWith("system."))
    .sort();
}

function rawCollectionMetric(database, name) {
  if (!collectionExists(database, name)) return null;
  const documents = database.getCollection(name).find({}).toArray();
  const indexes = database.getCollection(name).getIndexes().map(actualIndexSemantics)
    .sort((left, right) => left.name.localeCompare(right.name));
  return Object.freeze({
    name,
    count: documents.length,
    checksum: canonicalChecksum(documents),
    indexDigest: sha256(canonicalExtendedJson(indexes))
  });
}

function exactLegacySnapshot(database, manifest) {
  assertInitialInventory(database, manifest);
  const collections = applicationCollections(database)
    .map((name) => rawCollectionMetric(database, name));
  const presentSources = sourceNames(manifest)
    .filter((source) => collectionExists(database, source)).sort();
  return { presentSources, collections, kinds: kindMetricsFromLegacy(database, manifest) };
}

function sameValue(left, right) {
  return canonicalExtendedJson(left) === canonicalExtendedJson(right);
}

function requireSnapshotMatchesEvidence(database, manifest, evidence) {
  const current = exactLegacySnapshot(database, manifest);
  if (!sameValue(current.presentSources, evidence.presentSources)
      || !sameValue(current.collections, evidence.collections)
      || !sameValue(current.kinds, evidence.kinds)) {
    fail("Mongo protected evidence does not match the legacy inventory.");
  }
  return current;
}

function requireProtectedLegacyCollections(database, evidence) {
  for (const expected of evidence.collections) {
    const actual = rawCollectionMetric(database, expected.name);
    if (!actual || !sameValue(actual, expected)) {
      fail("Mongo protected legacy collection changed after preview.");
    }
  }
}

function assertInitialInventory(database, manifest) {
  const approved = new Set(sourceNames(manifest).concat(manifest.dropOnly));
  for (const name of applicationCollections(database)) {
    if (name.startsWith(STAGE_PREFIX) || name.startsWith(LEGACY_PREFIX) || !approved.has(name)) {
      fail("Mongo migration inventory contains an unexpected collection.");
    }
  }
}

function assertSupportedId(value) {
  if (value === null || value === undefined || Array.isArray(value)) {
    fail("Mongo source identity is invalid.");
  }
  const type = typeof value;
  if (type === "string") return;
  if (type === "number" && Number.isSafeInteger(value)) return;
  if (type !== "object") fail("Mongo source identity type is invalid.");
  const name = value && value.constructor ? value.constructor.name : "";
  if (!["ObjectId", "Long", "Int32", "Decimal128", "Binary", "UUID"].includes(name)) {
    fail("Mongo source identity type is invalid.");
  }
}

function sourceDocumentMatches(kind, document) {
  if (kind.sourceId === null) return true;
  return canonicalExtendedJson(document._id) === canonicalExtendedJson(kind.sourceId);
}

function sourceDocuments(database, kind) {
  if (!kind.source || !collectionExists(database, kind.source)) return [];
  return database.getCollection(kind.source).find({}).toArray()
    .filter((document) => sourceDocumentMatches(kind, document));
}

function validateSharedVehicleSource(database, manifest) {
  if (!collectionExists(database, "vehicle_import_state")) return;
  const kinds = manifest.kinds.filter((kind) => kind.source === "vehicle_import_state");
  for (const document of database.getCollection("vehicle_import_state").find({}).toArray()) {
    assertSupportedId(document._id);
    if (kinds.filter((kind) => sourceDocumentMatches(kind, document)).length !== 1) {
      fail("Mongo shared source identity is not approved.");
    }
  }
}

function assertV014Authority(database) {
  if (!collectionExists(database, "application_migrations")) {
    fail("Mongo V014 authority is absent.");
  }
  const migration = database.getCollection("application_migrations").findOne({ _id: V014_ID });
  const exactKeys = ["_id", "checksum", "completedAt", "description", "ownerToken", "startedAt", "status"];
  if (!migration || !sameValue(Object.keys(migration).sort(), exactKeys.sort())
      || migration.checksum !== V014_CHECKSUM || migration.description !== V014_DESCRIPTION
      || migration.status !== "APPLIED" || typeof migration.ownerToken !== "string"
      || migration.ownerToken.length === 0 || !(migration.startedAt instanceof Date)
      || !(migration.completedAt instanceof Date)) {
    fail("Mongo V014 authority is absent or malformed.");
  }
  if (!collectionExists(database, "music_runtime_state")) {
    fail("Mongo V014 authoritative source is absent.");
  }
  return migration;
}

function v014Evidence(database) {
  const migration = assertV014Authority(database);
  const checksum = (name) => {
    const metric = rawCollectionMetric(database, name);
    return metric ? metric.checksum : canonicalChecksum([]);
  };
  return Object.freeze({
    id: migration._id,
    checksum: migration.checksum,
    queueChecksum: checksum("music_queue_state"),
    radioChecksum: checksum("music_radio_state"),
    targetChecksum: checksum("music_runtime_state")
  });
}

function legacyShape(document) {
  assertSupportedId(document._id);
  const copy = {};
  for (const key of Object.keys(document)) copy[key] = document[key];
  return copy;
}

function envelope(kind, legacyDocument) {
  const source = legacyShape(legacyDocument);
  const legacyId = source._id;
  delete source._id;
  return {
    _id: { kind: kind.kind, legacyId },
    _kind: kind.kind,
    schemaVersion: kind.schemaVersion,
    payload: source
  };
}

function envelopeToLegacy(document, kind) {
  const keys = Object.keys(document);
  const id = document._id;
  if (canonicalExtendedJson(keys) !== canonicalExtendedJson(["_id", "_kind", "schemaVersion", "payload"])
      || !id || canonicalExtendedJson(Object.keys(id)) !== canonicalExtendedJson(["kind", "legacyId"])
      || id.kind !== kind.kind || document._kind !== kind.kind
      || document.schemaVersion !== kind.schemaVersion || !document.payload
      || typeof document.payload !== "object" || Array.isArray(document.payload)
      || Object.prototype.hasOwnProperty.call(document.payload, "_id")) {
    fail("Mongo staged envelope is malformed.");
  }
  const result = { _id: id.legacyId };
  for (const key of Object.keys(document.payload)) result[key] = document.payload[key];
  return result;
}

function kindMetricsFromLegacy(database, manifest) {
  return manifest.kinds.map((kind) => {
    const documents = kind.source ? sourceDocuments(database, kind).map(legacyShape) : [];
    return Object.freeze({ kind: kind.kind, count: documents.length, checksum: canonicalChecksum(documents) });
  });
}

function targetMetrics(database, manifest, resolveName) {
  const documentsByKind = new Map(manifest.kinds.map((kind) => [kind.kind, []]));
  for (const target of manifest.targets) {
    const name = resolveName(target);
    if (!collectionExists(database, name)) continue;
    const allowed = new Map(manifest.kinds.filter((kind) => kind.target === target)
      .map((kind) => [kind.kind, kind]));
    for (const document of database.getCollection(name).find({}).toArray()) {
      const kind = allowed.get(document._kind);
      if (!kind) fail("Mongo target contains an unknown kind.");
      if (kind.kind === LEDGER_KIND) {
        if (!sameValue(document._id, ledgerId()) || document.schemaVersion !== 1
            || !document.payload || typeof document.payload !== "object") {
          fail("Mongo cutover ledger envelope is malformed.");
        }
      } else {
        documentsByKind.get(kind.kind).push(envelopeToLegacy(document, kind));
      }
    }
  }
  return manifest.kinds.map((kind) => kind.kind === LEDGER_KIND
    ? Object.freeze({ kind: kind.kind, count: 1, checksum: "<ledger>" })
    : Object.freeze({
      kind: kind.kind,
      count: documentsByKind.get(kind.kind).length,
      checksum: canonicalChecksum(documentsByKind.get(kind.kind))
    }));
}

function kindMetricsFromTarget(database, manifest, staged) {
  return targetMetrics(database, manifest,
    (target) => (staged ? STAGE_PREFIX : "") + target);
}

function kindMetricsFromAvailableTarget(database, manifest) {
  return targetMetrics(database, manifest, (target) => {
    const staged = STAGE_PREFIX + target;
    return collectionExists(database, staged) ? staged : target;
  });
}

function indexKeysDocument(index) {
  const keys = {};
  for (const [path, direction] of index.keys) keys[path] = direction;
  return keys;
}

function indexOptions(index) {
  const options = { name: index.name };
  if (index.unique) options.unique = true;
  if (index.sparse) options.sparse = true;
  if (Object.keys(index.partialFilterExpression).length !== 0) {
    options.partialFilterExpression = index.partialFilterExpression;
  }
  if (index.expireAfterSeconds !== null) options.expireAfterSeconds = index.expireAfterSeconds;
  if (index.collation !== null) options.collation = index.collation;
  return options;
}

function expectedIndexSemantics(index) {
  return {
    name: index.name,
    key: indexKeysDocument(index),
    unique: index.name === "_id_" || index.unique,
    sparse: index.sparse,
    partialFilterExpression: index.partialFilterExpression,
    expireAfterSeconds: index.expireAfterSeconds,
    collation: index.collation
  };
}

function actualIndexSemantics(index) {
  return {
    name: index.name,
    key: index.key,
    unique: index.name === "_id_" || index.unique === true,
    sparse: index.sparse === true,
    partialFilterExpression: index.partialFilterExpression || {},
    expireAfterSeconds: index.expireAfterSeconds === undefined ? null : Number(index.expireAfterSeconds),
    collation: index.collation || null
  };
}

function indexMetrics(database, manifest, staged) {
  return manifest.targets.map((target) => {
    const name = staged ? STAGE_PREFIX + target : target;
    if (!collectionExists(database, name)) {
      return Object.freeze({ target, count: 0, digest: canonicalChecksum([]) });
    }
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
      .sort((left, right) => left.name.localeCompare(right.name));
    return Object.freeze({ target, count: actual.length, digest: sha256(canonicalExtendedJson(actual)) });
  });
}

function indexMetricsFromAvailableTarget(database, manifest) {
  return manifest.targets.map((target) => {
    const staged = STAGE_PREFIX + target;
    const name = collectionExists(database, staged) ? staged : target;
    if (!collectionExists(database, name)) {
      return Object.freeze({ target, count: 0, digest: canonicalChecksum([]) });
    }
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
        .sort((left, right) => left.name.localeCompare(right.name));
    return Object.freeze({
      target,
      count: actual.length,
      digest: sha256(canonicalExtendedJson(actual))
    });
  });
}

function requireExactIndexes(database, manifest, staged) {
  for (const target of manifest.targets) {
    const name = staged ? STAGE_PREFIX + target : target;
    if (!collectionExists(database, name)) fail("Mongo target collection is absent.");
    const expected = manifest.indexes.filter((index) => index.target === target)
      .map(expectedIndexSemantics).sort((left, right) => left.name.localeCompare(right.name));
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
      .sort((left, right) => left.name.localeCompare(right.name));
    if (canonicalExtendedJson(actual) !== canonicalExtendedJson(expected)) {
      fail("Mongo target indexes do not match the manifest.");
    }
  }
}

function requireExactAvailableIndexes(database, manifest) {
  for (const target of manifest.targets) {
    const staged = STAGE_PREFIX + target;
    const name = collectionExists(database, staged) ? staged : target;
    if (!collectionExists(database, name)) fail("Mongo target collection is absent.");
    const expected = manifest.indexes.filter((index) => index.target === target)
      .map(expectedIndexSemantics).sort((left, right) => left.name.localeCompare(right.name));
    const actual = database.getCollection(name).getIndexes().map(actualIndexSemantics)
      .sort((left, right) => left.name.localeCompare(right.name));
    if (!sameValue(actual, expected)) fail("Mongo target indexes do not match the manifest.");
  }
}

function result(command, state, kinds, indexes, nextOperation, complete, publishedEvidence = null) {
  const evidence = publishedEvidence || (typeof globalThis !== "undefined"
    ? globalThis.DOMAIN_COLLECTION_EVIDENCE || null : null);
  return Object.freeze({
    complete,
    database: command.database,
    action: command.action,
    state,
    manifestDigest: command.manifestDigest,
    backupIdentity: command.backupIdentity,
    evidenceDigest: evidence ? evidenceDigest(evidence) : null,
    evidence: publishedEvidence,
    kinds,
    indexes,
    nextOperation
  });
}

function ledgerId() {
  return { kind: LEDGER_KIND, legacyId: LEDGER_ID };
}

function ledgerNames() {
  return [STAGE_PREFIX + "application_migrations", "application_migrations"];
}

function findLedger(database) {
  const found = ledgerNames().filter((name) => collectionExists(database, name))
    .map((name) => ({ name, value: database.getCollection(name).findOne({
      _id: ledgerId(), _kind: LEDGER_KIND
    }) })).filter((entry) => entry.value);
  if (found.length !== 1) fail("Mongo cutover ledger is absent or ambiguous.");
  const payload = found[0].value.payload;
  const exactPayloadKeys = ["backupIdentity", "completed", "dropIndex", "evidenceDigest",
    "expectedKindMetrics", "intent", "legacyDropped", "manifestDigest", "ownerToken",
    "presentSources", "publishIndex", "release", "revision", "stageIndex", "state"];
  if (!payload || !sameValue(Object.keys(payload).sort(), exactPayloadKeys.sort())
      || payload.manifestDigest !== ManifestApi.DIGEST
      || !OWNER.test(payload.ownerToken) || !RELEASE.test(payload.release)
      || !BACKUP.test(payload.backupIdentity) || !DIGEST_PATTERN.test(payload.evidenceDigest)
      || !Array.isArray(payload.presentSources) || !Array.isArray(payload.expectedKindMetrics)
      || !Number.isSafeInteger(payload.revision) || payload.revision < 1
      || !(payload.intent === null || typeof payload.intent === "object")) {
    fail("Mongo cutover ledger is malformed.");
  }
  return { collection: found[0].name, document: found[0].value, payload };
}

function requireLedger(database, command) {
  const ledger = findLedger(database);
  if (ledger.payload.ownerToken !== command.ownerToken
      || ledger.payload.release !== command.release
      || ledger.payload.manifestDigest !== command.manifestDigest
      || ledger.payload.backupIdentity !== command.backupIdentity) {
    fail("Mongo cutover ledger ownership does not match.");
  }
  suppliedEvidence(command, ledger.payload.evidenceDigest);
  return ledger;
}

function suppliedEvidence(command, expectedDigest = null) {
  const evidence = typeof globalThis !== "undefined"
    ? globalThis.DOMAIN_COLLECTION_EVIDENCE : null;
  const digest = expectedDigest || (evidence ? evidenceDigest(evidence) : null);
  return requireProtectedEvidence(command, evidence, digest);
}

function buildDropCollections(manifest, presentSources) {
  return [...new Set(presentSources.filter((source) => !manifest.targets.includes(source))
    .concat(manifest.dropOnly)
    .concat(presentSources.filter((source) => manifest.targets.includes(source))
      .map((source) => LEGACY_PREFIX + source)))].sort();
}

function stagedTargetsAt(manifest, stageIndex) {
  const names = new Set([STAGE_PREFIX + "application_migrations"]);
  const operations = manifest.kinds.map((kind) => kind.target).concat(manifest.targets);
  for (let index = 0; index < Math.min(stageIndex, operations.length); index++) {
    names.add(STAGE_PREFIX + operations[index]);
  }
  return names;
}

function inventoryAt(ledger, manifest, effectApplied = false) {
  const names = new Set(suppliedEvidence({
    manifestDigest: ledger.payload.manifestDigest,
    release: ledger.payload.release,
    backupIdentity: ledger.payload.backupIdentity
  }, ledger.payload.evidenceDigest).collections.map((metric) => metric.name));
  const effectiveStageIndex = ledger.payload.stageIndex
    + (effectApplied && ledger.payload.intent && ledger.payload.intent.phase === "stage" ? 1 : 0);
  for (const name of stagedTargetsAt(manifest, effectiveStageIndex)) names.add(name);
  const publications = buildPublicationOperations(manifest, ledger.payload.presentSources);
  const publicationCount = ledger.payload.publishIndex
    + (effectApplied && ledger.payload.intent && ledger.payload.intent.phase === "publish" ? 1 : 0);
  for (let index = 0; index < publicationCount; index++) {
    names.delete(publications[index].from);
    names.add(publications[index].to);
  }
  const reverseCount = effectApplied && ledger.payload.intent && ledger.payload.intent.phase === "reverse"
    ? ledger.payload.publishIndex - 1 : ledger.payload.publishIndex;
  if (ledger.payload.state === "REVERSING" || ledger.payload.intent && ledger.payload.intent.phase === "reverse") {
    const forwardCount = Math.max(0, reverseCount);
    const reset = new Set(suppliedEvidence({
      manifestDigest: ledger.payload.manifestDigest,
      release: ledger.payload.release,
      backupIdentity: ledger.payload.backupIdentity
    }, ledger.payload.evidenceDigest).collections.map((metric) => metric.name));
    for (const name of stagedTargetsAt(manifest, effectiveStageIndex)) reset.add(name);
    for (let index = 0; index < forwardCount; index++) {
      reset.delete(publications[index].from);
      reset.add(publications[index].to);
    }
    names.clear();
    for (const name of reset) names.add(name);
  }
  const drops = buildDropCollections(manifest, ledger.payload.presentSources);
  const dropCount = ledger.payload.dropIndex
    + (effectApplied && ledger.payload.intent && ledger.payload.intent.phase === "drop" ? 1 : 0);
  for (let index = 0; index < dropCount; index++) names.delete(drops[index]);
  return [...names].sort();
}

function requireExactInventory(database, ledger, manifest) {
  const actual = applicationCollections(database);
  const before = inventoryAt(ledger, manifest, false);
  const after = ledger.payload.intent ? inventoryAt(ledger, manifest, true) : before;
  if (!sameValue(actual, before) && !sameValue(actual, after)) {
    fail("Mongo migration phase inventory is invalid.");
  }
}

function updateLedger(database, ledger, fields) {
  const set = {};
  for (const [key, value] of Object.entries(fields)) set["payload." + key] = value;
  const write = database.getCollection(ledger.collection).updateOne({
    _id: ledgerId(),
    _kind: LEDGER_KIND,
    "payload.ownerToken": ledger.payload.ownerToken,
    "payload.revision": ledger.payload.revision,
    "payload.intent": null
  }, { $set: set, $inc: { "payload.revision": 1 } });
  if (write.matchedCount !== 1 || write.modifiedCount !== 1) {
    fail("Mongo cutover ledger ownership was lost.");
  }
}

function maybeInterrupt(point) {
  if (typeof globalThis !== "undefined" && globalThis.DOMAIN_COLLECTION_INTERRUPT_AT === point) {
    fail("Mongo migration interruption was injected.");
  }
}

function effectIntent(ledger, phase, index, operation) {
  return Object.freeze({
    phase,
    index,
    ownerToken: ledger.payload.ownerToken,
    originalRevision: ledger.payload.revision,
    originalState: ledger.payload.state,
    operation
  });
}

function claimEffect(database, command, ledger, phase, index, operation) {
  if (ledger.payload.intent !== null) {
    const intent = ledger.payload.intent;
    if (intent.phase !== phase || intent.index !== index || intent.ownerToken !== command.ownerToken
        || intent.originalRevision + 1 !== ledger.payload.revision
        || intent.originalState !== ledger.payload.state || !sameValue(intent.operation, operation)) {
      fail("Mongo cutover effect intent is stale or malformed.");
    }
    return ledger;
  }
  const expected = effectIntent(ledger, phase, index, operation);
  const write = database.getCollection(ledger.collection).updateOne({
    _id: ledgerId(),
    _kind: LEDGER_KIND,
    "payload.ownerToken": command.ownerToken,
    "payload.revision": ledger.payload.revision,
    "payload.state": ledger.payload.state,
    "payload.intent": null
  }, { $set: { "payload.intent": expected }, $inc: { "payload.revision": 1 } });
  if (write.matchedCount !== 1 || write.modifiedCount !== 1) {
    fail("Mongo cutover effect ownership was lost.");
  }
  maybeInterrupt("after-intent");
  const claimed = requireLedger(database, command);
  if (!sameValue(claimed.payload.intent, expected)) {
    fail("Mongo cutover effect intent is malformed.");
  }
  return claimed;
}

function completeEffect(database, command, ledger, fields) {
  const set = { "payload.intent": null };
  for (const [key, value] of Object.entries(fields)) set["payload." + key] = value;
  const current = requireLedger(database, command);
  if (!sameValue(current.payload.intent, ledger.payload.intent)
      || current.payload.revision !== ledger.payload.revision) {
    fail("Mongo cutover effect ownership was lost.");
  }
  const write = database.getCollection(current.collection).updateOne({
    _id: ledgerId(),
    _kind: LEDGER_KIND,
    "payload.ownerToken": command.ownerToken,
    "payload.revision": current.payload.revision,
    "payload.intent": current.payload.intent
  }, { $set: set, $inc: { "payload.revision": 1 } });
  if (write.matchedCount !== 1 || write.modifiedCount !== 1) {
    fail("Mongo cutover effect ownership was lost.");
  }
  maybeInterrupt("after-reconcile");
  return requireLedger(database, command);
}

function preview(database, command, manifest) {
  assertInitialInventory(database, manifest);
  validateSharedVehicleSource(database, manifest);
  const snapshot = exactLegacySnapshot(database, manifest);
  const evidence = Object.freeze({
    version: 1,
    manifestDigest: command.manifestDigest,
    release: command.release,
    backupIdentity: command.backupIdentity,
    presentSources: snapshot.presentSources,
    kinds: snapshot.kinds,
    collections: snapshot.collections,
    v014: v014Evidence(database)
  });
  requireProtectedEvidence(command, evidence, evidenceDigest(evidence));
  const metrics = snapshot.kinds;
  return result(command, "PREVIEWED", metrics, indexMetrics(database, manifest, false),
    "stage", true, evidence);
}

function initializeLedger(database, command, manifest) {
  assertInitialInventory(database, manifest);
  validateSharedVehicleSource(database, manifest);
  const evidence = suppliedEvidence(command);
  requireSnapshotMatchesEvidence(database, manifest, evidence);
  if (!sameValue(v014Evidence(database), evidence.v014)) {
    fail("Mongo V014 evidence does not match the protected inventory.");
  }
  const name = STAGE_PREFIX + "application_migrations";
  if (collectionExists(database, name)) fail("Mongo staging residue is present.");
  database.createCollection(name);
  const presentSources = evidence.presentSources;
  const payload = {
    state: "STAGING",
    manifestDigest: command.manifestDigest,
    ownerToken: command.ownerToken,
    release: command.release,
    backupIdentity: command.backupIdentity,
    evidenceDigest: evidenceDigest(evidence),
    revision: 1,
    stageIndex: 0,
    publishIndex: 0,
    dropIndex: 0,
    completed: false,
    legacyDropped: false,
    intent: null,
    presentSources,
    expectedKindMetrics: evidence.kinds
  };
  database.getCollection(name).insertOne({
    _id: ledgerId(), _kind: LEDGER_KIND, schemaVersion: 1, payload
  });
  return findLedger(database);
}

function stageKind(database, manifest, kind) {
  const target = STAGE_PREFIX + kind.target;
  if (!collectionExists(database, target)) database.createCollection(target);
  const collection = database.getCollection(target);
  for (const source of sourceDocuments(database, kind)) {
    const staged = envelope(kind, source);
    const existing = collection.findOne({ _id: staged._id });
    if (existing && canonicalExtendedJson(existing) !== canonicalExtendedJson(staged)) {
      fail("Mongo staging identity collision was detected.");
    }
    if (!existing) collection.insertOne(staged);
  }
}

function stageIndexes(database, manifest, target) {
  const name = STAGE_PREFIX + target;
  if (!collectionExists(database, name)) database.createCollection(name);
  for (const index of manifest.indexes.filter((candidate) =>
    candidate.target === target && candidate.name !== "_id_")) {
    database.getCollection(name).createIndex(indexKeysDocument(index), indexOptions(index));
  }
}

function stage(database, command, manifest) {
  let ledger;
  try {
    ledger = requireLedger(database, command);
  } catch (failure) {
    if (!String(failure.message).includes("absent")) throw failure;
    ledger = initializeLedger(database, command, manifest);
  }
  requireExactInventory(database, ledger, manifest);
  if (ledger.payload.state === "STAGED") {
    return result(command, "STAGED", kindMetricsFromTarget(database, manifest, true),
      indexMetrics(database, manifest, true), "verify-stage", true);
  }
  if (ledger.payload.state !== "STAGING") fail("Mongo cutover ledger state is invalid for staging.");
  const operationCount = manifest.kinds.length + manifest.targets.length;
  const index = ledger.payload.stageIndex;
  if (!Number.isSafeInteger(index) || index < 0 || index > operationCount) {
    fail("Mongo staging progress is invalid.");
  }
  let operation;
  if (index < manifest.kinds.length) {
    const kind = manifest.kinds[index];
    operation = { kind: "stage-kind", target: kind.target, domainKind: kind.kind };
  } else if (index < operationCount) {
    operation = { kind: "stage-indexes", target: manifest.targets[index - manifest.kinds.length] };
  } else {
    operation = { kind: "stage-complete" };
  }
  ledger = claimEffect(database, command, ledger, "stage", index, operation);
  if (index < manifest.kinds.length) {
    stageKind(database, manifest, manifest.kinds[index]);
  } else if (index < operationCount) {
    stageIndexes(database, manifest, manifest.targets[index - manifest.kinds.length]);
  }
  maybeInterrupt("after-effect");
  requireExactInventory(database, ledger, manifest);
  const next = Math.min(index + 1, operationCount);
  const completedLedger = completeEffect(database, command, ledger, {
    stageIndex: next,
    state: next === operationCount ? "STAGED" : "STAGING"
  });
  const current = completedLedger.payload;
  return result(command, current.state, kindMetricsFromTarget(database, manifest, true),
    indexMetrics(database, manifest, true),
    current.state === "STAGED" ? "verify-stage" : "stage", current.state === "STAGED");
}

function requireEquivalentMetrics(left, right) {
  const byKind = new Map(right.map((entry) => [entry.kind, entry]));
  for (const source of left) {
    if (source.kind === LEDGER_KIND) continue;
    const target = byKind.get(source.kind);
    if (!target || source.count !== target.count || source.checksum !== target.checksum) {
      fail("Mongo kind count or checksum does not match.");
    }
  }
}

function verifyStage(database, command, manifest) {
  const ledger = requireLedger(database, command);
  requireExactInventory(database, ledger, manifest);
  requireProtectedLegacyCollections(database, suppliedEvidence(command, ledger.payload.evidenceDigest));
  if (ledger.payload.state === "STAGE_VERIFIED") {
    const staged = kindMetricsFromTarget(database, manifest, true);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, staged);
    requireExactIndexes(database, manifest, true);
    return result(command, "STAGE_VERIFIED", staged, indexMetrics(database, manifest, true),
      "publish-next", true);
  }
  if (!["STAGED", "LEGACY_ACTIVE"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for stage verification.");
  }
  const source = kindMetricsFromLegacy(database, manifest);
  const staged = kindMetricsFromTarget(database, manifest, true);
  requireEquivalentMetrics(source, staged);
  requireExactIndexes(database, manifest, true);
  updateLedger(database, ledger, { state: "STAGE_VERIFIED", expectedKindMetrics: source });
  return result(command, "STAGE_VERIFIED", staged, indexMetrics(database, manifest, true),
    "publish-next", true);
}

function rename(database, operation) {
  const fromExists = collectionExists(database, operation.from);
  const toExists = collectionExists(database, operation.to);
  if (fromExists && !toExists) {
    const outcome = database.getSiblingDB("admin").runCommand({
      renameCollection: database.getName() + "." + operation.from,
      to: database.getName() + "." + operation.to,
      dropTarget: false
    });
    if (!outcome.ok) fail("Mongo collection rename failed.");
    return;
  }
  if (!fromExists && toExists) return;
  fail("Mongo collection rename state is invalid.");
}

function publishNext(database, command, manifest) {
  let ledger = requireLedger(database, command);
  requireExactInventory(database, ledger, manifest);
  if (ledger.payload.state === "PUBLISHED") {
    const available = kindMetricsFromAvailableTarget(database, manifest);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, available);
    requireExactAvailableIndexes(database, manifest);
    return result(command, "PUBLISHED", available,
      indexMetricsFromAvailableTarget(database, manifest), "verify-live", true);
  }
  if (!["STAGE_VERIFIED", "PUBLISHING"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for publication.");
  }
  if (ledger.payload.state === "STAGE_VERIFIED") {
    updateLedger(database, ledger, { state: "PUBLISHING" });
    ledger = requireLedger(database, command);
  }
  const operations = buildPublicationOperations(manifest, ledger.payload.presentSources);
  const index = ledger.payload.publishIndex;
  if (!Array.isArray(operations) || !Number.isSafeInteger(index) || index < 0 || index > operations.length) {
    fail("Mongo publication progress is invalid.");
  }
  const available = kindMetricsFromAvailableTarget(database, manifest);
  requireEquivalentMetrics(ledger.payload.expectedKindMetrics, available);
  requireExactAvailableIndexes(database, manifest);
  if (index >= operations.length) fail("Mongo publication progress is invalid.");
  ledger = claimEffect(database, command, ledger, "publish", index, operations[index]);
  rename(database, operations[index]);
  maybeInterrupt("after-effect");
  requireExactInventory(database, ledger, manifest);
  const next = Math.min(index + 1, operations.length);
  const completedLedger = completeEffect(database, command, ledger, {
    publishIndex: next,
    state: next === operations.length ? "PUBLISHED" : "PUBLISHING"
  });
  const current = completedLedger.payload;
  return result(command, current.state, kindMetricsFromAvailableTarget(database, manifest),
    indexMetricsFromAvailableTarget(database, manifest),
    current.state === "PUBLISHED" ? "verify-live" : "publish-next",
    current.state === "PUBLISHED");
}

function verifyLive(database, command, manifest) {
  const ledger = requireLedger(database, command);
  requireExactInventory(database, ledger, manifest);
  if (ledger.payload.state === "TARGET_ACTIVE" && ledger.payload.completed === true) {
    const live = kindMetricsFromTarget(database, manifest, false);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, live);
    requireExactIndexes(database, manifest, false);
    return result(command, "TARGET_ACTIVE", live, indexMetrics(database, manifest, false),
      ledger.payload.legacyDropped === true ? null : "drop-legacy", true);
  }
  if (ledger.payload.state !== "PUBLISHED") fail("Mongo cutover ledger state is invalid for live verification.");
  const source = ledger.payload.expectedKindMetrics;
  if (!Array.isArray(source) || source.length !== manifest.kinds.length) {
    fail("Mongo cutover ledger verification evidence is malformed.");
  }
  const live = kindMetricsFromTarget(database, manifest, false);
  requireEquivalentMetrics(source, live);
  requireExactIndexes(database, manifest, false);
  updateLedger(database, ledger, { state: "TARGET_ACTIVE", completed: true });
  return result(command, "TARGET_ACTIVE", live, indexMetrics(database, manifest, false),
    "drop-legacy", true);
}

function dropLegacy(database, command, manifest) {
  let ledger = requireLedger(database, command);
  requireExactInventory(database, ledger, manifest);
  if (ledger.payload.state !== "TARGET_ACTIVE" || ledger.payload.completed !== true) {
    fail("Mongo cutover ledger state is invalid for legacy deletion.");
  }
  const drops = buildDropCollections(manifest, ledger.payload.presentSources);
  const index = ledger.payload.dropIndex;
  if (!Array.isArray(drops) || !Number.isSafeInteger(index) || index < 0 || index > drops.length) {
    fail("Mongo legacy deletion progress is invalid.");
  }
  if (ledger.payload.legacyDropped === true && index === drops.length) {
    const live = kindMetricsFromTarget(database, manifest, false);
    requireEquivalentMetrics(ledger.payload.expectedKindMetrics, live);
    requireExactIndexes(database, manifest, false);
    if (manifest.targets.length !== 14) fail("Mongo target inventory cardinality is invalid.");
    return result(command, "TARGET_ACTIVE", live,
      indexMetrics(database, manifest, false), null, true);
  }
  if (index >= drops.length) fail("Mongo legacy deletion progress is invalid.");
  const live = kindMetricsFromTarget(database, manifest, false);
  requireEquivalentMetrics(ledger.payload.expectedKindMetrics, live);
  requireExactIndexes(database, manifest, false);
  const evidence = suppliedEvidence(command, ledger.payload.evidenceDigest);
  const name = drops[index];
  if (name === "music_queue_state" && rawCollectionMetric(database, name)
      && rawCollectionMetric(database, name).checksum !== evidence.v014.queueChecksum) {
    fail("Mongo V014 queue evidence changed before deletion.");
  }
  if (name === "music_radio_state" && rawCollectionMetric(database, name)
      && rawCollectionMetric(database, name).checksum !== evidence.v014.radioChecksum) {
    fail("Mongo V014 radio evidence changed before deletion.");
  }
  const operation = { kind: "drop", name };
  ledger = claimEffect(database, command, ledger, "drop", index, operation);
  if (collectionExists(database, name)) {
    const acknowledged = database.getCollection(name).drop();
    if (acknowledged !== true || collectionExists(database, name)) {
      fail("Mongo allowlisted legacy deletion was not acknowledged.");
    }
  }
  maybeInterrupt("after-effect");
  requireExactInventory(database, ledger, manifest);
  const next = Math.min(index + 1, drops.length);
  const completedLedger = completeEffect(database, command, ledger,
    { dropIndex: next, legacyDropped: next === drops.length });
  const current = completedLedger.payload;
  return result(command, "TARGET_ACTIVE", kindMetricsFromTarget(database, manifest, false),
    indexMetrics(database, manifest, false),
    current.legacyDropped ? null : "drop-legacy", current.legacyDropped === true);
}

function reverseNext(database, command, manifest) {
  let ledger = requireLedger(database, command);
  requireExactInventory(database, ledger, manifest);
  if (ledger.payload.dropIndex !== 0 || ledger.payload.legacyDropped === true) {
    fail("Mongo publication cannot be reversed after legacy deletion.");
  }
  if (ledger.payload.state === "LEGACY_ACTIVE") {
    return result(command, "LEGACY_ACTIVE", kindMetricsFromAvailableTarget(database, manifest),
      indexMetricsFromAvailableTarget(database, manifest), null, true);
  }
  if (!["PUBLISHING", "PUBLISHED", "TARGET_ACTIVE", "REVERSING"].includes(ledger.payload.state)) {
    fail("Mongo cutover ledger state is invalid for reversal.");
  }
  if (ledger.payload.state !== "REVERSING") {
    updateLedger(database, ledger, { state: "REVERSING", completed: false });
    ledger = requireLedger(database, command);
  }
  const index = ledger.payload.publishIndex;
  if (!Number.isSafeInteger(index) || index < 0) fail("Mongo reversal progress is invalid.");
  if (index <= 0) fail("Mongo reversal progress is invalid.");
  const available = kindMetricsFromAvailableTarget(database, manifest);
  requireEquivalentMetrics(ledger.payload.expectedKindMetrics, available);
  requireExactAvailableIndexes(database, manifest);
  const publications = buildPublicationOperations(manifest, ledger.payload.presentSources);
  const forward = publications[index - 1];
  if (!forward) fail("Mongo reversal progress is invalid.");
  const operation = { kind: "rename", from: forward.to, to: forward.from };
  ledger = claimEffect(database, command, ledger, "reverse", index, operation);
  rename(database, operation);
  maybeInterrupt("after-effect");
  requireExactInventory(database, ledger, manifest);
  const next = Math.max(0, index - 1);
  completeEffect(database, command, ledger, {
    publishIndex: next,
    state: next === 0 ? "LEGACY_ACTIVE" : "REVERSING"
  });
  return result(command, next === 0 ? "LEGACY_ACTIVE" : "REVERSING",
    kindMetricsFromAvailableTarget(database, manifest),
    indexMetricsFromAvailableTarget(database, manifest),
    next === 0 ? null : "reverse-next", next === 0);
}

function restoreVerify(database, command, manifest) {
  const evidence = suppliedEvidence(command);
  validateSharedVehicleSource(database, manifest);
  const snapshot = requireSnapshotMatchesEvidence(database, manifest, evidence);
  if (!sameValue(v014Evidence(database), evidence.v014)) {
    fail("Mongo restored V014 evidence does not match the protected inventory.");
  }
  return result(command, "LEGACY_RESTORE_VERIFIED", snapshot.kinds,
    indexMetrics(database, manifest, false), null, true);
}

function execute(rootDatabase, args) {
  const command = parseCommand(args);
  const manifest = ManifestApi.requireDigest(command.manifestDigest);
  const database = rootDatabase.getSiblingDB(command.database);
  const handlers = Object.freeze({
    preview, stage,
    "verify-stage": verifyStage,
    "publish-next": publishNext,
    "verify-live": verifyLive,
    "drop-legacy": dropLegacy,
    "reverse-next": reverseNext,
    "restore-verify": restoreVerify
  });
  return handlers[command.action](database, command, manifest);
}

const exported = Object.freeze({
  parseCommand,
  redactedFailure,
  evidenceDigest,
  requireProtectedEvidence,
  canonicalExtendedJson,
  canonicalChecksum,
  buildPublicationOperations,
  reversePublicationOperations,
  execute
});

if (typeof module !== "undefined" && module.exports) module.exports = exported;
if (typeof globalThis !== "undefined") globalThis.DomainCollectionMigration = exported;

if (typeof db !== "undefined" && typeof globalThis !== "undefined"
    && Array.isArray(globalThis.DOMAIN_COLLECTION_ARGS)) {
  try {
    print(JSON.stringify(execute(db, globalThis.DOMAIN_COLLECTION_ARGS)));
  } catch (failure) {
    print(JSON.stringify(redactedFailure(globalThis.DOMAIN_COLLECTION_ARGS)));
    if (typeof quit === "function") quit(1);
    throw new Error("Domain collection migration failed.");
  }
}
