ALTER TABLE review_m0_case
    ADD CONSTRAINT fk_review_m0_case_current_checklist
    FOREIGN KEY (current_checklist_snapshot_id) REFERENCES review_m0_checklist_snapshot(checklist_snapshot_id);

ALTER TABLE review_m0_case
    ADD CONSTRAINT fk_review_m0_case_current_report
    FOREIGN KEY (current_report_id) REFERENCES review_m0_report(report_id);

ALTER TABLE review_m0_checklist_revision
    ADD CONSTRAINT fk_review_m0_checklist_revision_job
    FOREIGN KEY (extraction_job_id) REFERENCES review_m0_job(job_id);

ALTER TABLE review_m0_remote_consent
    ADD CONSTRAINT fk_review_m0_remote_consent_job
    FOREIGN KEY (consumed_by_job_id) REFERENCES review_m0_job(job_id);
