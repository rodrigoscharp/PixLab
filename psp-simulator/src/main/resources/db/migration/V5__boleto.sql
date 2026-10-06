create sequence nosso_numero_seq start with 10000000001;

create table boleto (
    nosso_numero    bigint        primary key,
    beneficiario    varchar(77),
    valor           numeric(15,2) not null check (valor > 0),
    vencimento      date          not null,
    multa_pct       numeric(5,2)  not null default 0,
    juros_mes_pct   numeric(5,2)  not null default 0,
    status          varchar(20)   not null,
    codigo_barras   varchar(44)   not null unique,
    linha_digitavel varchar(47)   not null,
    created_at      timestamptz   not null default now()
);

-- Cada evento do título vira um par T+U no arquivo de retorno do dia da ocorrência.
create table boleto_ocorrencia (
    id              bigserial     primary key,
    nosso_numero    bigint        not null references boleto (nosso_numero),
    codigo          varchar(2)    not null,
    data_ocorrencia date          not null,
    data_credito    date,
    valor_pago      numeric(15,2) not null default 0,
    juros_multa     numeric(15,2) not null default 0,
    created_at      timestamptz   not null default now()
);

create index boleto_ocorrencia_data_idx on boleto_ocorrencia (data_ocorrencia, id);
