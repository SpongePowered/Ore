# --- !Ups
DROP VIEW v_logged_actions;

ALTER TABLE logged_actions
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN user_id TYPE BIGINT,
  ALTER COLUMN action_context_id TYPE BIGINT,
  ALTER COLUMN user_id SET NOT NULL,
  ALTER COLUMN action SET NOT NULL,
  ALTER COLUMN action_context SET NOT NULL,
  ALTER COLUMN action_context_id SET NOT NULL,
  ALTER COLUMN new_state SET NOT NULL,
  ALTER COLUMN old_state SET NOT NULL;

ALTER TABLE notifications
  ALTER COLUMN message_args SET NOT NULL;

ALTER TABLE project_api_keys
  ALTER COLUMN project_id SET NOT NULL;

ALTER TABLE project_channels
  ALTER COLUMN id TYPE BIGINT;

ALTER TABLE project_flags
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN resolved_by TYPE BIGINT,
  ALTER COLUMN comment SET NOT NULL;

ALTER TABLE project_pages
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN parent_id DROP NOT NULL,
  ALTER COLUMN parent_id DROP DEFAULT;

UPDATE project_pages SET parent_id = NULL WHERE parent_id = -1;

ALTER TABLE project_settings
  ALTER COLUMN forum_sync SET NOT NULL;

ALTER TABLE project_tags
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN version_ids SET NOT NULL,
  ALTER COLUMN name SET NOT NULL,
  ALTER COLUMN data SET NOT NULL,
  ALTER COLUMN color SET NOT NULL;

ALTER TABLE project_version_downloads
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE project_version_reviews
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN version_id TYPE BIGINT,
  ALTER COLUMN user_id TYPE BIGINT,
  ALTER COLUMN comment SET NOT NULL;

ALTER TABLE project_version_visibility_changes
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN created_by TYPE BIGINT,
  ALTER COLUMN version_id TYPE BIGINT,
  ALTER COLUMN resolved_by TYPE BIGINT,
  ALTER COLUMN comment SET NOT NULL,
  ALTER COLUMN visibility SET NOT NULL;

UPDATE project_versions SET reviewer_id = NULL WHERE reviewer_id = -1;

ALTER TABLE project_versions
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN tags TYPE BIGINT [],
  ALTER COLUMN author_id DROP DEFAULT,
  ALTER COLUMN author_id SET NOT NULL,
  ALTER COLUMN reviewer_id DROP DEFAULT,
  ALTER COLUMN tags SET NOT NULL,
  ALTER COLUMN is_non_reviewed SET NOT NULL;

ALTER TABLE project_views
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE project_visibility_changes
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN created_by TYPE BIGINT,
  ALTER COLUMN project_id TYPE BIGINT,
  ALTER COLUMN resolved_by TYPE BIGINT,
  ALTER COLUMN comment SET NOT NULL,
  ALTER COLUMN visibility SET NOT NULL;

UPDATE projects SET topic_id = NULL WHERE topic_id = -1;
UPDATE projects SET post_id = NULL WHERE post_id = -1;

ALTER TABLE projects
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN topic_id TYPE INTEGER,
  ALTER COLUMN post_id TYPE INTEGER,
  ALTER COLUMN topic_id DROP DEFAULT,
  ALTER COLUMN post_id DROP DEFAULT,
  ALTER COLUMN is_topic_dirty SET NOT NULL;

ALTER TABLE user_project_roles
  ALTER COLUMN id TYPE BIGINT,
  ALTER COLUMN project_id SET NOT NULL;

ALTER TABLE USERS
  ALTER COLUMN is_locked SET NOT NULL;

--Generated from IntelliJ
create view v_logged_actions as
  SELECT a.id,
         a.created_at,
         a.user_id,
         a.address,
         a.action,
         a.action_context,
         a.action_context_id,
         a.new_state,
         a.old_state,
         u.id              AS u_id,
         u.name            AS u_name,
         p.id              AS p_id,
         p.plugin_id       AS p_plugin_id,
         p.slug            AS p_slug,
         p.owner_name      AS p_owner_name,
         pv.id             AS pv_id,
         pv.version_string AS pv_version_string,
         pp.id             AS pp_id,
         pp.name           AS pp_name,
         pp.slug           AS pp_slug,
         s.id              AS s_id,
         s.name            AS s_name,
         CASE
           WHEN (a.action_context = 0) THEN (a.action_context_id) :: bigint
           WHEN (a.action_context = 1) THEN COALESCE(pv.project_id, ('-1' :: integer) :: bigint)
           WHEN (a.action_context = 2) THEN COALESCE(pp.project_id, ('-1' :: integer) :: bigint)
           ELSE ('-1' :: integer) :: bigint
             END           AS filter_project,
         CASE
           WHEN (a.action_context = 1) THEN COALESCE(pv.id, a.action_context_id)
           ELSE '-1' :: integer
             END           AS filter_version,
         CASE
           WHEN (a.action_context = 2) THEN COALESCE(pp.id, '-1' :: integer)
           ELSE '-1' :: integer
             END           AS filter_page,
         CASE
           WHEN (a.action_context = 3) THEN a.action_context_id
           WHEN (a.action_context = 4) THEN a.action_context_id
           ELSE '-1' :: integer
             END           AS filter_subject,
         a.action          AS filter_action
  FROM (((((logged_actions a
      LEFT JOIN users u ON ((a.user_id = u.id)))
      LEFT JOIN projects p ON ((
    CASE
    WHEN ((a.action_context = 0) AND (a.action_context_id = p.id))
      THEN 1
    WHEN ((a.action_context = 1) AND
          ((SELECT pvin.project_id FROM project_versions pvin WHERE (pvin.id = a.action_context_id)) = p.id))
      THEN 1
    WHEN ((a.action_context = 2) AND
          ((SELECT ppin.project_id FROM project_pages ppin WHERE (ppin.id = a.action_context_id)) = p.id))
      THEN 1
    ELSE 0
    END = 1)))
      LEFT JOIN project_versions pv ON (((a.action_context = 1) AND (a.action_context_id = pv.id))))
      LEFT JOIN project_pages pp ON (((a.action_context = 2) AND (a.action_context_id = pp.id))))
      LEFT JOIN users s ON ((
    CASE
    WHEN ((a.action_context = 3) AND (a.action_context_id = s.id))
      THEN 1
    WHEN ((a.action_context = 4) AND (a.action_context_id = s.id))
      THEN 1
    ELSE 0
    END = 1)));

# --- !Downs

DROP VIEW v_logged_actions;

ALTER TABLE logged_actions
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN user_id TYPE INTEGER,
  ALTER COLUMN action_context_id TYPE INTEGER,
  ALTER COLUMN user_id DROP NOT NULL,
  ALTER COLUMN action DROP NOT NULL,
  ALTER COLUMN action_context DROP NOT NULL,
  ALTER COLUMN action_context_id DROP NOT NULL,
  ALTER COLUMN new_state DROP NOT NULL,
  ALTER COLUMN old_state DROP NOT NULL;

ALTER TABLE notifications
  ALTER COLUMN message_args DROP NOT NULL;

ALTER TABLE project_api_keys
  ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE project_channels
  ALTER COLUMN id TYPE INTEGER;

ALTER TABLE project_flags
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN resolved_by TYPE INTEGER,
  ALTER COLUMN comment DROP NOT NULL;

UPDATE project_pages SET parent_id = -1 WHERE parent_id IS NULL;

ALTER TABLE project_pages
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN parent_id SET DEFAULT -1,
  ALTER COLUMN parent_id SET NOT NULL;

ALTER TABLE project_settings
  ALTER COLUMN forum_sync DROP NOT NULL;

ALTER TABLE project_tags
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN version_ids DROP NOT NULL,
  ALTER COLUMN name DROP NOT NULL,
  ALTER COLUMN data DROP NOT NULL,
  ALTER COLUMN color DROP NOT NULL;

ALTER TABLE project_version_downloads
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE project_version_reviews
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN version_id TYPE INTEGER,
  ALTER COLUMN user_id TYPE INTEGER,
  ALTER COLUMN comment DROP NOT NULL;

ALTER TABLE project_version_visibility_changes
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN created_by TYPE INTEGER,
  ALTER COLUMN version_id TYPE INTEGER,
  ALTER COLUMN resolved_by TYPE INTEGER,
  ALTER COLUMN comment DROP NOT NULL,
  ALTER COLUMN visibility DROP NOT NULL;

UPDATE project_versions SET reviewer_id = -1 WHERE reviewer_id IS NULL;

ALTER TABLE project_versions
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN tags TYPE INTEGER [],
  ALTER COLUMN author_id SET DEFAULT -1,
  ALTER COLUMN author_id DROP NOT NULL,
  ALTER COLUMN reviewer_id SET DEFAULT -1,
  ALTER COLUMN tags DROP NOT NULL,
  ALTER COLUMN is_non_reviewed DROP NOT NULL;

ALTER TABLE project_views
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN created_at DROP NOT NULL;

ALTER TABLE project_visibility_changes
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN created_by TYPE INTEGER,
  ALTER COLUMN project_id TYPE INTEGER,
  ALTER COLUMN resolved_by TYPE INTEGER,
  ALTER COLUMN comment DROP NOT NULL,
  ALTER COLUMN visibility DROP NOT NULL;

UPDATE projects SET topic_id = -1 WHERE topic_id IS NULL;
UPDATE projects SET post_id = -1 WHERE post_id IS NULL ;

ALTER TABLE projects
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN topic_id TYPE BIGINT,
  ALTER COLUMN post_id TYPE BIGINT,
  ALTER COLUMN topic_id SET DEFAULT -1,
  ALTER COLUMN post_id SET DEFAULT -1,
  ALTER COLUMN is_topic_dirty DROP NOT NULL;

ALTER TABLE user_project_roles
  ALTER COLUMN id TYPE INTEGER,
  ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE USERS
  ALTER COLUMN is_locked DROP NOT NULL;

--Generated from IntelliJ
create view v_logged_actions as
  SELECT a.id,
         a.created_at,
         a.user_id,
         a.address,
         a.action,
         a.action_context,
         a.action_context_id,
         a.new_state,
         a.old_state,
         u.id              AS u_id,
         u.name            AS u_name,
         p.id              AS p_id,
         p.plugin_id       AS p_plugin_id,
         p.slug            AS p_slug,
         p.owner_name      AS p_owner_name,
         pv.id             AS pv_id,
         pv.version_string AS pv_version_string,
         pp.id             AS pp_id,
         pp.name           AS pp_name,
         pp.slug           AS pp_slug,
         s.id              AS s_id,
         s.name            AS s_name,
         CASE
           WHEN (a.action_context = 0) THEN (a.action_context_id) :: bigint
           WHEN (a.action_context = 1) THEN COALESCE(pv.project_id, ('-1' :: integer) :: bigint)
           WHEN (a.action_context = 2) THEN COALESCE(pp.project_id, ('-1' :: integer) :: bigint)
           ELSE ('-1' :: integer) :: bigint
             END           AS filter_project,
         CASE
           WHEN (a.action_context = 1) THEN COALESCE(pv.id, a.action_context_id)
           ELSE '-1' :: integer
             END           AS filter_version,
         CASE
           WHEN (a.action_context = 2) THEN COALESCE(pp.id, '-1' :: integer)
           ELSE '-1' :: integer
             END           AS filter_page,
         CASE
           WHEN (a.action_context = 3) THEN a.action_context_id
           WHEN (a.action_context = 4) THEN a.action_context_id
           ELSE '-1' :: integer
             END           AS filter_subject,
         a.action          AS filter_action
  FROM (((((logged_actions a
      LEFT JOIN users u ON ((a.user_id = u.id)))
      LEFT JOIN projects p ON ((
    CASE
    WHEN ((a.action_context = 0) AND (a.action_context_id = p.id))
      THEN 1
    WHEN ((a.action_context = 1) AND
          ((SELECT pvin.project_id FROM project_versions pvin WHERE (pvin.id = a.action_context_id)) = p.id))
      THEN 1
    WHEN ((a.action_context = 2) AND
          ((SELECT ppin.project_id FROM project_pages ppin WHERE (ppin.id = a.action_context_id)) = p.id))
      THEN 1
    ELSE 0
    END = 1)))
      LEFT JOIN project_versions pv ON (((a.action_context = 1) AND (a.action_context_id = pv.id))))
      LEFT JOIN project_pages pp ON (((a.action_context = 2) AND (a.action_context_id = pp.id))))
      LEFT JOIN users s ON ((
    CASE
    WHEN ((a.action_context = 3) AND (a.action_context_id = s.id))
      THEN 1
    WHEN ((a.action_context = 4) AND (a.action_context_id = s.id))
      THEN 1
    ELSE 0
    END = 1)));
