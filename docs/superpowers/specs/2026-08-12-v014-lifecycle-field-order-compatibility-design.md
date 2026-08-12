# V014 Lifecycle Field-Order Compatibility Design

## Context

The guarded production domain-collection preview reaches the migration engine but fails its V014 authority check. The durable production record is authoritative and otherwise exact: it has the approved migration ID, checksum, description, `APPLIED` status, non-empty owner token, BSON dates, expected `_class`, and the required `music_runtime_state` collection. Its ordered fields are:

```text
_id, checksum, description, status, ownerToken, startedAt, _class, completedAt
```

The validator currently accepts only the fresh `MappingMongoConverter` order:

```text
_id, checksum, description, status, ownerToken, startedAt, completedAt, _class
```

The durable order is produced when the running record is inserted with `_class` and `completedAt` is added by the later completion update.

## Before-Edit Brief

- **Behavior:** The read-only domain-collection preview must accept the exact durable V014 production representation as well as the exact fresh-converter representation, without accepting any other record shape.
- **Invariants:** Field names, BSON types, migration ID, checksum, description, status, `_class`, non-empty owner token, and authoritative music-state existence remain exact; reordered or extra fields outside the two proven lifecycle orders remain invalid.
- **Boundary/API:** Only the mongosh V014 authority validator and its executable contracts change; command arguments, result JSON, manifest digest, ledger format, startup gate, backup, cutover, rollback, and deletion interfaces remain unchanged.
- **Effects and failures:** Validation is read-only. A matching record proceeds to protected preview evidence; every malformed or unproven representation still returns the same redacted failure and performs no mutation.
- **Tests and evidence:** Add a Node regression using the observed durable order and prove RED against the current validator, keep the fresh-converter case green, keep malformed/reordered negatives, rerun Node, Pester/disposable Mongo, and the guarded production preview before any cutover.

## Considered Approaches

1. **Accept two exact lifecycle orders (selected).** This matches the two witnessed creation/update paths while keeping the boundary closed.
2. **Compare a sorted field set.** This is simpler but unnecessarily accepts arbitrary order and weakens the canonical persisted-record contract.
3. **Rewrite the production migration record.** This mutates protected historical authority merely to satisfy a validator and is therefore rejected.

## Design

Represent the accepted key orders as two immutable literal arrays next to the V014 constants. The validator checks the document's ordered keys against either literal order, then applies every existing value and BSON-type check unchanged. No normalization occurs and no compatibility state escapes the validator.

The executable test fixture will create a V014 record in the exact durable order observed in production. A second case will retain the fresh-converter order. Existing wrong-checksum, wrong-class, missing-field, extra-field, and arbitrary-reordering failures remain fail-closed.

## Delivery and Recovery

Ship the fix as a narrow follow-up PR. After CI and merge, retry `mongo-consolidation-preview` under elevation. Only a successful protected preview permits the existing backup, isolated candidate, and confirmed cutover sequence. No manual MongoDB repair or direct production write is part of this change.
