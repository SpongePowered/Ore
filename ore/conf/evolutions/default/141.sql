# --- !Ups

ALTER TABLE users
    DROP COLUMN is_locked;

# --- !Downs

ALTER TABLE users
    ADD COLUMN is_locked BOOLEAN NOT NULL DEFAULT FALSE;
