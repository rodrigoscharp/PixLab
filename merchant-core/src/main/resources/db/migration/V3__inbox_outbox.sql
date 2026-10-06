-- Único caminho de entrada de eventos externos (ADR-0002). UNIQUE garante idempotência sob concorrência.
create table webhook_inbox (
    id              bigserial   primary key,
    source          varchar(20) not null,
    event_key       varchar(80) not null,
    payload         jsonb       not null,
    status          varchar(20) not null default 'PENDING',
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text,
    received_at     timestamptz not null default now(),
    processed_at    timestamptz,
    unique (source, event_key)
);

create index webhook_inbox_pending_idx on webhook_inbox (next_attempt_at, id) where status = 'PENDING';

-- Transactional outbox (ADR-0003): gravada na mesma transação do lançamento, publicada por um relay.
create table outbox (
    id           bigserial   primary key,
    aggregate    varchar(40) not null,
    aggregate_id varchar(80) not null,
    type         varchar(60) not null,
    payload      jsonb       not null,
    created_at   timestamptz not null default now(),
    published_at timestamptz
);

create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- Referência externa do lançamento (e2eId, rtrId, nosso número...), para auditoria e conciliação.
alter table ledger_entry add column ref varchar(80);
create index ledger_entry_ref_idx on ledger_entry (ref);
