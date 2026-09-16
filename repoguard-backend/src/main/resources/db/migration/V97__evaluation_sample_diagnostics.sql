-- Only bounded sample identifiers, statuses and usage summaries; never source or provider payloads.
alter table llm_evaluation_run add column diagnostics_json json null;
