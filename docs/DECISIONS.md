# Architectural Decisions

Proposal is the backbone of the archive.

Award derives from Proposal.

Protocol links to Proposal.

Negotiation links to Proposal.

Use JdbcClient.

Use the custom SQL migration runner and `public.schema_migration`.

Protocol Archive is the canonical human-subjects archive. Its identity is
`PROTOCOL_NUMBER` (family), `SEQUENCE_NUMBER` (business version), and
`PROTOCOL_ID` (physical Oracle row).

The legacy flat IRB implementation is deprecated. Preserve it without new
features until Protocol reaches feature parity, then retire it in a dedicated
cleanup milestone.

Protocol child `PROTOCOL_ID` values are not universally reliable version
parents. Select a measured strategy per child: `NUMBER_SEQUENCE`,
`DIRECT_PROTOCOL_ID`, or `OWNER_CHAIN`. Personnel uses `NUMBER_SEQUENCE`;
`protocol_id` is resolved from `(PROTOCOL_NUMBER, SEQUENCE_NUMBER)` and the
original Oracle value is retained as `source_protocol_id`.

Use Hexagonal Architecture.

Never duplicate Award logic.

Mirror existing patterns.
