# --- !Ups

DROP VIEW v_logged_actions;

ALTER TABLE projects
    DROP COLUMN slug,
    ADD COLUMN slug TEXT NOT NULL GENERATED ALWAYS AS ( left(
            regexp_replace(translate(lower(name), ' ', '-'), '[^a-z\-._0-9]', '', 'g'), 32) ) STORED,
    ADD CONSTRAINT projects_owner_name_slug_key UNIQUE (owner_name, slug);

create view v_logged_actions(id, created_at, user_id, user_name, address, action, context_type, new_state, old_state, p_id, p_plugin_id, p_slug, p_owner_name, pv_id, pv_version_string, pp_id, pp_name, pp_slug, s_id, s_name) as
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                       AS user_name,
       a.address,
       a.action,
       0                            AS context_type,
       a.new_state,
       a.old_state,
       p.id                         AS p_id,
       p.plugin_id                  AS p_plugin_id,
       p.slug                       AS p_slug,
       ou.name                      AS p_owner_name,
       NULL::BIGINT                 AS pv_id,
       NULL::CHARACTER VARYING(255) AS pv_version_string,
       NULL::BIGINT                 AS pp_id,
       NULL::CHARACTER VARYING(255) AS pp_name,
       NULL::CHARACTER VARYING(255) AS pp_slug,
       NULL::BIGINT                 AS s_id,
       NULL::CHARACTER VARYING(255) AS s_name
FROM logged_actions_project a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       1                       AS context_type,
       a.new_state,
       a.old_state,
       p.id                    AS p_id,
       p.plugin_id             AS p_plugin_id,
       p.slug                  AS p_slug,
       ou.name                 AS p_owner_name,
       pv.id                   AS pv_id,
       pv.version_string       AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       NULL::BIGINT            AS s_id,
       NULL::CHARACTER VARYING AS s_name
FROM logged_actions_version a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN project_versions pv ON a.version_id = pv.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       2                       AS context_type,
       a.new_state,
       a.old_state,
       p.id                    AS p_id,
       p.plugin_id             AS p_plugin_id,
       p.slug                  AS p_slug,
       ou.name                 AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       pp.id                   AS pp_id,
       pp.name                 AS pp_name,
       pp.slug                 AS pp_slug,
       NULL::BIGINT            AS s_id,
       NULL::CHARACTER VARYING AS s_name
FROM logged_actions_page a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN project_pages pp ON a.page_id = pp.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       3                       AS context_type,
       a.new_state,
       a.old_state,
       NULL::BIGINT            AS p_id,
       NULL::CHARACTER VARYING AS p_plugin_id,
       NULL::CHARACTER VARYING AS p_slug,
       NULL::CHARACTER VARYING AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       s.id                    AS s_id,
       s.name                  AS s_name
FROM logged_actions_user a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN users s ON a.subject_id = s.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       4                       AS context_type,
       a.new_state,
       a.old_state,
       NULL::BIGINT            AS p_id,
       NULL::CHARACTER VARYING AS p_plugin_id,
       NULL::CHARACTER VARYING AS p_slug,
       NULL::CHARACTER VARYING AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       s.id                    AS s_id,
       s.name                  AS s_name
FROM logged_actions_organization a
         LEFT JOIN organizations o ON a.organization_id = o.id
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN users s ON o.user_id = s.id;

# --- !Downs

DROP VIEW v_logged_actions;

ALTER TABLE projects
    ADD COLUMN slug_2 TEXT;

UPDATE projects
SET slug_2 = slug;

ALTER TABLE projects
    DROP COLUMN slug;

ALTER TABLE projects
    RENAME COLUMN slug_2 TO slug;

ALTER TABLE projects
    ADD CONSTRAINT projects_owner_name_slug_key UNIQUE (owner_name, slug);

CREATE VIEW v_logged_actions
            (id, created_at, user_id, user_name, address, action, context_type, new_state, old_state, p_id, p_plugin_id,
             p_slug, p_owner_name, pv_id, pv_version_string, pp_id, pp_name, pp_slug, s_id, s_name)
AS
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                       AS user_name,
       a.address,
       a.action,
       0                            AS context_type,
       a.new_state,
       a.old_state,
       p.id                         AS p_id,
       p.plugin_id                  AS p_plugin_id,
       p.slug                       AS p_slug,
       ou.name                      AS p_owner_name,
       NULL::BIGINT                 AS pv_id,
       NULL::CHARACTER VARYING(255) AS pv_version_string,
       NULL::BIGINT                 AS pp_id,
       NULL::CHARACTER VARYING(255) AS pp_name,
       NULL::CHARACTER VARYING(255) AS pp_slug,
       NULL::BIGINT                 AS s_id,
       NULL::CHARACTER VARYING(255) AS s_name
FROM logged_actions_project a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       1                       AS context_type,
       a.new_state,
       a.old_state,
       p.id                    AS p_id,
       p.plugin_id             AS p_plugin_id,
       p.slug                  AS p_slug,
       ou.name                 AS p_owner_name,
       pv.id                   AS pv_id,
       pv.version_string       AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       NULL::BIGINT            AS s_id,
       NULL::CHARACTER VARYING AS s_name
FROM logged_actions_version a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN project_versions pv ON a.version_id = pv.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       2                       AS context_type,
       a.new_state,
       a.old_state,
       p.id                    AS p_id,
       p.plugin_id             AS p_plugin_id,
       p.slug                  AS p_slug,
       ou.name                 AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       pp.id                   AS pp_id,
       pp.name                 AS pp_name,
       pp.slug                 AS pp_slug,
       NULL::BIGINT            AS s_id,
       NULL::CHARACTER VARYING AS s_name
FROM logged_actions_page a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN project_pages pp ON a.page_id = pp.id
         LEFT JOIN projects p ON a.project_id = p.id
         LEFT JOIN users ou ON p.owner_id = ou.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       3                       AS context_type,
       a.new_state,
       a.old_state,
       NULL::BIGINT            AS p_id,
       NULL::CHARACTER VARYING AS p_plugin_id,
       NULL::CHARACTER VARYING AS p_slug,
       NULL::CHARACTER VARYING AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       s.id                    AS s_id,
       s.name                  AS s_name
FROM logged_actions_user a
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN users s ON a.subject_id = s.id
UNION ALL
SELECT a.id,
       a.created_at,
       a.user_id,
       u.name                  AS user_name,
       a.address,
       a.action,
       4                       AS context_type,
       a.new_state,
       a.old_state,
       NULL::BIGINT            AS p_id,
       NULL::CHARACTER VARYING AS p_plugin_id,
       NULL::CHARACTER VARYING AS p_slug,
       NULL::CHARACTER VARYING AS p_owner_name,
       NULL::BIGINT            AS pv_id,
       NULL::CHARACTER VARYING AS pv_version_string,
       NULL::BIGINT            AS pp_id,
       NULL::CHARACTER VARYING AS pp_name,
       NULL::CHARACTER VARYING AS pp_slug,
       s.id                    AS s_id,
       s.name                  AS s_name
FROM logged_actions_organization a
         LEFT JOIN organizations o ON a.organization_id = o.id
         LEFT JOIN users u ON a.user_id = u.id
         LEFT JOIN users s ON o.user_id = s.id;