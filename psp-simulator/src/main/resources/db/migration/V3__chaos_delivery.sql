-- Pix sem cobrança (cenário PIX-UNKNOWN) existe no mundo real: pagamento para a chave com txid que não é nosso.
alter table pix drop constraint pix_txid_fkey;

-- Entregas de webhook agendadas na mesma transação do Pix; o dispatcher envia quando vencem.
create table webhook_delivery (
    id              bigserial    primary key,
    e2e_id          varchar(32)  not null,
    url             varchar(500) not null,
    payload         text         not null,
    forged          boolean      not null default false,
    fake_failures   integer      not null default 0,
    status          varchar(20)  not null default 'PENDING',
    attempts        integer      not null default 0,
    next_attempt_at timestamptz  not null,
    last_status     integer,
    last_error      text,
    created_at      timestamptz  not null default now()
);

create index webhook_delivery_pending_idx on webhook_delivery (next_attempt_at, id) where status = 'PENDING';
create index webhook_delivery_e2e_idx on webhook_delivery (e2e_id);
