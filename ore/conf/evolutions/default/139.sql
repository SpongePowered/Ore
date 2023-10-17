# --- !Ups

ALTER TABLE project_pages
    ALTER COLUMN contents DROP NOT NULL,
    ADD CONSTRAINT homepage_contents_check CHECK ( name != 'Home' OR NOT contents IS NULL );

# --- !Downs

UPDATE project_pages
SET contents = ''
WHERE contents IS NULL;

ALTER TABLE project_pages
    DROP CONSTRAINT homepage_contents_check,
    ALTER COLUMN contents SET NOT NULL;
