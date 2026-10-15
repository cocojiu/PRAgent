-- Null preserves the legacy timeline adapter; new workers write bounded structured evidence.
ALTER TABLE review_task ADD COLUMN failure_details_json JSON NULL;
