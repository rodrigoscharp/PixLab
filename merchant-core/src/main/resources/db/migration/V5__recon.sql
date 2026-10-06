create table recon_run (
    id               bigserial   primary key,
    window_start     timestamptz not null,
    window_end       timestamptz not null,
    status           varchar(20) not null,
    job_execution_id bigint,
    started_at       timestamptz not null default now(),
    finished_at      timestamptz,
    summary          jsonb
);

create table recon_item (
    id            bigserial     primary key,
    run_id        bigint        not null references recon_run (id),
    key           varchar(80)   not null,
    result        varchar(30)   not null,
    psp_amount    numeric(15,2),
    ledger_amount numeric(15,2),
    action        varchar(40),
    details       jsonb,
    unique (run_id, key)
);

create index recon_item_result_idx on recon_item (run_id, result);
