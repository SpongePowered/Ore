# --- !Ups

DROP VIEW organization_trust;
DROP VIEW project_trust;
DROP VIEW project_members_all;

DROP TABLE project_members;
DROP TABLE organization_members;

DROP TRIGGER project_owner_name_updater ON projects;

DROP FUNCTION update_project_name_trigger();
CREATE FUNCTION update_project_name_trigger() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    UPDATE projects p SET owner_name = u.name FROM users u WHERE p.id = new.id AND u.id = new.owner_id;;

    RETURN new;;
END;;
$$;

CREATE TRIGGER project_owner_name_updater
    AFTER UPDATE
        OF owner_id
    ON projects
    FOR EACH ROW
    WHEN (old.owner_id <> new.owner_id)
EXECUTE PROCEDURE update_project_name_trigger();

DELETE
FROM user_project_roles upr1 USING user_project_roles upr2 JOIN roles r2 ON upr2.role_type = r2.name, roles r1
WHERE r1.id > r2.id
  AND upr1.role_type = r1.name
  AND upr1.project_id = upr2.project_id
  AND upr1.user_id = upr2.user_id;

DELETE
FROM user_organization_roles uor1 USING user_organization_roles uor2 JOIN roles r2 ON uor2.role_type = r2.name, roles r1
WHERE r1.id > r2.id
  AND uor1.role_type = r1.name
  AND uor1.organization_id = uor2.organization_id
  AND uor1.user_id = uor2.user_id;

ALTER TABLE user_project_roles
    DROP CONSTRAINT user_project_roles_user_id_role_type_id_project_id_key,
    ADD CONSTRAINT user_project_roles_user_id_project_id_key UNIQUE (project_id, user_id);

ALTER TABLE user_organization_roles
    DROP CONSTRAINT user_organization_roles_user_id_role_type_id_organization_id_ke,
    ADD CONSTRAINT user_organization_roles_user_id_organization_id_key UNIQUE (organization_id, user_id);

CREATE VIEW organization_trust AS
SELECT ro.organization_id,
       ro.user_id,
       COALESCE(bit_or(r.permission), '0'::BIT(64)) AS permission
FROM user_organization_roles ro
         JOIN roles r ON ro.role_type::TEXT = r.name::TEXT
WHERE ro.is_accepted
GROUP BY ro.organization_id, ro.user_id;

CREATE VIEW project_trust AS
SELECT rp.project_id,
       rp.user_id,
       COALESCE(bit_or(r.permission), '0'::BIT(64)) AS permission
FROM user_project_roles rp
         JOIN roles r ON rp.role_type::TEXT = r.name::TEXT
WHERE rp.is_accepted
GROUP BY rp.project_id, rp.user_id;

CREATE VIEW project_members_all AS
SELECT p.id,
       pm.user_id
FROM projects p
         LEFT JOIN user_project_roles pm ON p.id = pm.project_id
UNION
SELECT p.id,
       om.user_id
FROM projects p
         LEFT JOIN user_organization_roles om ON p.owner_id = om.organization_id
WHERE om.user_id IS NOT NULL;

DELETE
FROM user_project_roles upr USING projects p
WHERE upr.role_type = 'Project_Owner'
  AND upr.project_id = p.id
  AND upr.user_id != p.owner_id;

INSERT INTO roles (id, name, category, title, color, is_assignable, permission)
VALUES (29, 'Project_Admin', 'project', 'Admin', 'transparent', TRUE,
        ((1::BIT(64) << 2) | (1::BIT(64) << 4) | (1::BIT(64) << 5) | (1::BIT(64) << 7) | 1::BIT(64) << 12) | (1::BIT(64) << 13) | (1::BIT(64) << 14) | (1::BIT(64) << 15) | (1::BIT(64) << 9));

# --- !Downs
DROP VIEW organization_trust;
DROP VIEW project_trust;
DROP VIEW project_members_all;

DELETE FROM roles WHERE id = 29;

DROP TRIGGER project_owner_name_updater ON projects;

DROP FUNCTION update_project_name_trigger();
CREATE FUNCTION update_project_name_trigger() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    UPDATE projects p SET name = u.name FROM users u WHERE p.id = new.id AND u.id = new.owner_id;;
END;;
$$;

CREATE TRIGGER project_owner_name_updater
    AFTER UPDATE
        OF owner_id
    ON projects
    FOR EACH ROW
    WHEN (old.owner_id <> new.owner_id)
EXECUTE PROCEDURE update_project_name_trigger();

CREATE TABLE project_members
(
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
    PRIMARY KEY (project_id, user_id)
);

CREATE TABLE organization_members
(
    user_id         BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations ON DELETE CASCADE,
    PRIMARY KEY (user_id, organization_id)
);

ALTER TABLE user_project_roles
    ADD CONSTRAINT user_project_roles_user_id_role_type_id_project_id_key UNIQUE (project_id, role_type, user_id),
    DROP CONSTRAINT user_project_roles_user_id_project_id_key;

ALTER TABLE user_organization_roles
    ADD CONSTRAINT user_organization_roles_user_id_role_type_id_organization_id_ke UNIQUE (organization_id, role_type, user_id),
    DROP CONSTRAINT user_organization_roles_user_id_organization_id_key;

INSERT INTO project_members (project_id, user_id)
SELECT project_id, user_id
FROM user_project_roles;
INSERT INTO organization_members (organization_id, user_id)
SELECT organization_id, user_id
FROM user_organization_roles;

CREATE VIEW organization_trust AS
SELECT om.organization_id,
       om.user_id,
       COALESCE(bit_or(r.permission), '0'::BIT(64)) AS permission
FROM organization_members om
         JOIN user_organization_roles ro
              ON om.organization_id = ro.organization_id AND om.user_id = ro.user_id AND ro.is_accepted
         JOIN roles r ON ro.role_type::TEXT = r.name::TEXT
GROUP BY om.organization_id, om.user_id;

CREATE VIEW project_trust AS
SELECT pm.project_id,
       pm.user_id,
       COALESCE(bit_or(r.permission), '0'::BIT(64)) AS permission
FROM project_members pm
         JOIN user_project_roles rp ON pm.project_id = rp.project_id AND pm.user_id = rp.user_id AND rp.is_accepted
         JOIN roles r ON rp.role_type::TEXT = r.name::TEXT
GROUP BY pm.project_id, pm.user_id;

CREATE VIEW project_members_all AS
SELECT p.id,
       pm.user_id
FROM projects p
         LEFT JOIN project_members pm ON p.id = pm.project_id
UNION
SELECT p.id,
       om.user_id
FROM projects p
         LEFT JOIN organization_members om ON p.owner_id = om.organization_id
WHERE om.user_id IS NOT NULL;
