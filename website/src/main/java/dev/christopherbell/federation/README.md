# Federation

Federation is an operator-gated ActivityPub surface. Production enables the
read-only discovery surface while inbound and outbound remain independently
disabled. Enabling outbound also requires discovery, an encryption secret, a
not-before timestamp, and at least one controlled peer.

`discovery` exposes WebFinger, NodeInfo, actor, outbox, follower, and following
documents. `identity` owns account-bound encrypted RSA keys and signs exact
serialized request bytes. `outbound` owns canonical Create activities, creation-
time eligibility, durable Mongo delivery jobs, fresh DNS validation, pinned
connections, bounded retries, and the outbound kill switch.

Historical posts are ineligible because a missing `federationOutboundEligible`
field reads as false. Only posts explicitly marked at creation are scanned. Each
post/peer Create job is idempotent, claimed with a lease, and stores only bounded
status metadata—never payloads, response bodies, signatures, or private keys.

The production initializer creates or reuses one dedicated 32-byte identity-
encryption key under the ACL-protected production config directory unless an
explicit secret override is supplied. The value is never logged or committed.
`APP_FEDERATION_DISCOVERY_ENABLED=false` is the immediate discovery kill switch.

Production keeps `inbound-enabled` and `outbound-enabled` false and the
controlled peer list empty. The development loopback exception requires an HTTP
loopback public origin plus an explicit local flag; production configuration
always disables it.
