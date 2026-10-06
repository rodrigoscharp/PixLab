create table charge (
    id          bigserial     primary key,
    txid        varchar(35)   not null unique,
    amount      numeric(15,2) not null check (amount > 0),
    description varchar(140),
    status      varchar(30)   not null,
    expires_at  timestamptz   not null,
    version     bigint        not null default 0,
    created_at  timestamptz   not null default now()
);

create table payment (
    id         bigserial     primary key,
    charge_id  bigint        references charge (id),
    e2e_id     varchar(32)   not null unique check (length(e2e_id) = 32),
    amount     numeric(15,2) not null check (amount > 0),
    paid_at    timestamptz   not null,
    created_at timestamptz   not null default now()
);

create index payment_charge_id_idx on payment (charge_id);

-- Imutável: correções são feitas por estorno. Cada linha é débito OU crédito.
create table ledger_entry (
    id         bigserial     primary key,
    tx_id      uuid          not null,
    account    varchar(40)   not null,
    debit      numeric(15,2) not null default 0 check (debit >= 0),
    credit     numeric(15,2) not null default 0 check (credit >= 0),
    created_at timestamptz   not null default now(),
    check ((debit > 0) <> (credit > 0))
);

create index ledger_entry_tx_id_idx on ledger_entry (tx_id);
create index ledger_entry_account_idx on ledger_entry (account);
