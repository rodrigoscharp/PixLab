create table refund (
    id           bigserial     primary key,
    payment_id   bigint        not null references payment (id),
    refund_id    varchar(35)   not null,
    rtr_id       varchar(32)   unique,
    amount       numeric(15,2) not null check (amount > 0),
    status       varchar(20)   not null,
    requested_by varchar(10)   not null,
    created_at   timestamptz   not null default now(),
    updated_at   timestamptz   not null default now(),
    version      bigint        not null default 0,
    unique (payment_id, refund_id)
);

-- Eventos de devolução usam a chave e2eId:id:status (até 32 + 35 + 16 + 2 caracteres).
alter table webhook_inbox alter column event_key type varchar(120);
