-- Add immutable verifier and aggregation provenance to evaluation reports.
-- Legacy rows are explicitly marked unknown; new runs must provide both values.
alter table llm_evaluation_report
    add column verifier_version varchar(96) not null default 'legacy-unknown' after code_revision,
    add column aggregation_version varchar(96) not null default 'legacy-unknown' after verifier_version;
