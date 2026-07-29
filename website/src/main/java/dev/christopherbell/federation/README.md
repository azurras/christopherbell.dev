# Federation

Federation is an operator-gated ActivityPub surface. Discovery, inbound, and
outbound switches default to off. Enabling outbound also requires discovery,
an encryption secret, a not-before timestamp, and at least one controlled peer.

`discovery` exposes WebFinger, NodeInfo, actor, outbox, follower, and following
documents. `identity` owns account-bound encrypted RSA keys and signs exact
serialized request bytes. `outbound` owns canonical Create activities, creation-
time eligibility, durable Mongo delivery jobs, fresh DNS validation, pinned
connections, bounded retries, and the outbound kill switch.

Historical posts are ineligible because a missing `federationOutboundEligible`
field reads as false. Only posts explicitly marked at creation are scanned. Each
post/peer Create job is idempotent, claimed with a lease, and stores only bounded
status metadata—never payloads, response bodies, signatures, or private keys.

Production keeps `outbound-enabled` false and the controlled peer list empty.
The development loopback exception requires an HTTP loopback public origin plus
an explicit local flag; production configuration always disables it.
