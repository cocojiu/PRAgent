ALTER TABLE review_policy_config
    ADD COLUMN cached_input_token_price_per_million DECIMAL(12, 4) NULL;

ALTER TABLE review_task
    ADD COLUMN llm_cost_snapshot_json JSON NULL;

ALTER TABLE review_execution_attempt
    ADD COLUMN cost_snapshot_json JSON NULL;
