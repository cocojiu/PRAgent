-- Allow completed small-sample evaluations to remain explicit, comparable and non-promotable.
-- Existing reports retain their original status and dataset kind.
alter table llm_evaluation_report
    drop check chk_llm_evaluation_report_status,
    drop check chk_llm_evaluation_report_kind,
    add constraint chk_llm_evaluation_report_status
        check (status in ('COMPLETED', 'PROVISIONAL', 'FAILED', 'CANCELLED')),
    add constraint chk_llm_evaluation_report_kind
        check (dataset_kind in ('REAL_PR', 'PROVISIONAL_REAL_PR', 'OFFLINE_SYNTHETIC'));
