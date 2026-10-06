-- Contexto W3C do trace de quem entregou o evento, retomado pelo processador da inbox.
alter table webhook_inbox add column trace_parent varchar(55);
