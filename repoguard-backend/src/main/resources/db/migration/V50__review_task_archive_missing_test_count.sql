alter table review_task_archive_summary
    add column missing_test_count int not null default 0 after finding_count;
