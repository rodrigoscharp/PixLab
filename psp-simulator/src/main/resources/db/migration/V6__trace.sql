-- Contexto W3C do trace de quem agendou a entrega, retomado pelo dispatcher.
alter table webhook_delivery add column trace_parent varchar(55);
