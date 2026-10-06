create table devolucao (
    e2e_id      varchar(32)   not null references pix (e2e_id),
    id          varchar(35)   not null,
    rtr_id      varchar(32)   not null unique check (length(rtr_id) = 32),
    valor       numeric(15,2) not null check (valor > 0),
    natureza    varchar(20)   not null,
    status      varchar(20)   not null,
    motivo      varchar(140),
    solicitacao timestamptz   not null,
    resolve_at  timestamptz,
    primary key (e2e_id, id)
);

create index devolucao_pendente_idx on devolucao (resolve_at) where status = 'EM_PROCESSAMENTO';

-- Chave Pix do recebedor que recebeu o Pix; usada para notificar devoluções.
alter table pix add column chave varchar(77);
update pix p set chave = c.chave from cob c where c.txid = p.txid;
alter table pix alter column chave set not null;
