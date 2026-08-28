alter table notification_event
    add column trace_id varchar(128) null after tenant_id;
