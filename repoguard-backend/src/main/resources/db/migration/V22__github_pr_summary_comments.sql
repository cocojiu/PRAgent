alter table github_comment_publication
    drop foreign key fk_github_comment_publication_finding;

alter table github_comment_publication
    modify finding_id bigint null;

alter table github_comment_publication
    add constraint fk_github_comment_publication_finding foreign key (finding_id) references review_finding(id);

alter table github_comment_publication_batch_item
    drop foreign key fk_github_comment_publication_batch_item_finding;

alter table github_comment_publication_batch_item
    modify finding_id bigint null;

alter table github_comment_publication_batch_item
    add constraint fk_github_comment_publication_batch_item_finding foreign key (finding_id) references review_finding(id);
