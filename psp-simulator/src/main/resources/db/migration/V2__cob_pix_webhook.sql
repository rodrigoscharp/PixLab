create table webhook (
    chave       varchar(77)  primary key,
    webhook_url varchar(500) not null,
    criacao     timestamptz  not null
);

create table cob (
    txid                varchar(35)   primary key,
    chave               varchar(77)   not null,
    valor               numeric(15,2) not null check (valor > 0),
    solicitacao_pagador varchar(140),
    status              varchar(40)   not null,
    criacao             timestamptz   not null,
    expiracao           integer       not null check (expiracao > 0)
);

-- Extrato do PSP: um registro por Pix liquidado.
create table pix (
    e2e_id       varchar(32)   primary key check (length(e2e_id) = 32),
    txid         varchar(35)   not null references cob (txid),
    valor        numeric(15,2) not null check (valor > 0),
    horario      timestamptz   not null,
    info_pagador varchar(140)
);

create index pix_txid_idx on pix (txid);
create index pix_horario_idx on pix (horario);
