# --- !Ups

CREATE INDEX project_views_individual_cookie_idx ON project_views_individual (cookie);
CREATE INDEX project_views_individual_processed_idx ON project_views_individual (processed);

CREATE INDEX project_versions_downloads_individual_cookie_idx ON project_versions_downloads_individual (cookie);
CREATE INDEX project_versions_downloads_individual_processed_idx ON project_versions_downloads_individual (processed);

# --- !Downs

DROP INDEX project_views_individual_cookie_idx;
DROP INDEX project_views_individual_processed_idx;

DROP INDEX project_versions_downloads_individual_cookie_idx;
DROP INDEX project_versions_downloads_individual_processed_idx;
