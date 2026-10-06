alter table charge add column kind varchar(10) not null default 'PIX';
alter table charge add column nosso_numero varchar(20) unique;
alter table charge add column due_date date;
alter table charge add column fine_pct numeric(5,2);
alter table charge add column interest_pct numeric(5,2);
alter table charge add column barcode varchar(44);
alter table charge add column digitable_line varchar(47);

-- Pagamento de boleto não tem e2eId; a referência é nosso número + id da ocorrência no banco.
alter table payment alter column e2e_id drop not null;
alter table payment add column boleto_ref varchar(60) unique;
alter table payment add constraint payment_ref_check check (e2e_id is not null or boleto_ref is not null);

-- Idempotência por arquivo: o mesmo conteúdo (sha256) só é ingerido uma vez.
create table return_file (
    id          bigserial    primary key,
    sha256      varchar(64)  not null unique,
    name        varchar(200),
    records     integer      not null default 0,
    occurrences integer      not null default 0,
    new_events  integer      not null default 0,
    errors      jsonb        not null default '[]',
    received_at timestamptz  not null default now()
);

-- Ocorrências lidas de cada arquivo, para conciliar arquivo × ledger.
create table return_file_record (
    file_id    bigint        not null references return_file (id),
    event_key  varchar(60)   not null,
    code       varchar(2)    not null,
    amount     numeric(15,2) not null,
    primary key (file_id, event_key)
);
