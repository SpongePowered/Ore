package db

import ore.db.DbRef
import ore.models.Job
import ore.models.admin._
import ore.models.api.ProjectApiKey
import ore.models.project._
import ore.models.user._
import ore.models.user.role.{DbRole, OrganizationUserRole, ProjectUserRole}

import doobie.implicits._
import doobie.postgres.implicits._
import doobie.postgres.circe.jsonb.implicits._
import org.junit.runner._
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SchemaSpec extends DbSpec {

  test("Project") {
    check(sql"""|SELECT plugin_id, owner_name, owner_id, name,
                |category, description, topic_id, post_id, visibility,
                |notes, keywords, homepage, issues, source, support, license_name, license_url, 
                |forum_sync FROM projects""".stripMargin.query[Project])
  }

  test("Project watchers") {
    check(sql"""SELECT project_id, user_id FROM project_watchers""".query[(DbRef[Project], DbRef[User])])
  }

  test("Project stars") {
    check(sql"""SELECT user_id, project_id FROM project_stars""".query[(DbRef[User], DbRef[Project])])
  }

  test("Page") {
    check(sql"""|SELECT project_id, parent_id, name, slug,
                |is_deletable, contents FROM project_pages""".stripMargin.query[Page])
  }

  test("Version") {
    check(
      sql"""|SELECT project_id, version_string, dependency_ids, dependency_versions, file_size, hash,
            |author_id, description, review_state, reviewer_id, approved_at, visibility, file_name,
            |create_forum_post, post_id, uses_mixin, stability, release_type,
            |legacy_channel_name, legacy_channel_color FROM project_versions""".stripMargin.query[Version]
    )
  }

  test("DownloadWarning") {
    check(sql"""|SELECT expiration, token, version_id, address, is_confirmed,
                |download_id FROM project_version_download_warnings""".stripMargin.query[DownloadWarning])
  }

  test("UnsafeDownload") {
    check(
      sql"""|SELECT user_id, address, download_type FROM project_version_unsafe_downloads""".stripMargin
        .query[UnsafeDownload]
    )
  }

  /* Can't check this because id in user is private
  test("User") {
    check(
      sql"""|SELECT id, full_name, name, email, tagline, join_date, read_prompts, pgp_pub_key,
            |last_pgp_pub_key_update, is_locked, language FROM users""".stripMargin.query[User]
    )
  }
   */

  test("Session") {
    check(sql"""SELECT expiration, user_id, token FROM user_sessions""".query[Session])
  }

  test("SignOn") {
    check(sql"""SELECT nonce, is_completed FROM user_sign_ons""".query[SignOn])
  }

  /* Can't check this because id in org is private
  test("Organization") {
    check(sql"""SELECT id, name, user_id FROM organizations""".query[Organization])
  }
   */

  test("OrganizationRole") {
    check(sql"""|SELECT user_id, organization_id, role_type,
                |is_accepted FROM user_organization_roles""".stripMargin.query[OrganizationUserRole])
  }

  test("ProjectRole") {
    check(sql"""|SELECT user_id, project_id, role_type,
                |is_accepted FROM user_project_roles""".stripMargin.query[ProjectUserRole])
  }

  test("Notifiation") {
    check(sql"""|SELECT user_id, origin_id, notification_type, message_args, action,
                |read FROM notifications""".stripMargin.query[Notification])
  }

  test("Flag") {
    check(sql"""|SELECT project_id, user_id, reason, comment, is_resolved, resolved_at,
                |resolved_by FROM project_flags""".stripMargin.query[Flag])
  }

  test("ProjectApiKey") {
    check(sql"""SELECT project_id, value FROM project_api_keys""".query[ProjectApiKey])
  }

  test("Review") {
    check(sql"""SELECT version_id, user_id, ended_at, comment FROM project_version_reviews""".query[Review])
  }

  test("ProjectVisibilityChange") {
    check(sql"""|SELECT created_by, project_id, comment, resolved_at, resolved_by,
                |visibility FROM project_visibility_changes""".stripMargin.query[ProjectVisibilityChange])
  }

  test("LoggedActionProject") {
    check(
      sql"""|SELECT user_id, address, action, project_id, new_state,
            |old_state FROM logged_actions_project""".stripMargin.query[LoggedActionProject]
    )
  }

  test("LoggedActionVersion") {
    check(
      sql"""|SELECT user_id, address, action, version_id, new_state,
            |old_state, project_id FROM logged_actions_version""".stripMargin.query[LoggedActionVersion]
    )
  }

  test("LoggedActionPage") {
    check(
      sql"""|SELECT user_id, address, action, page_id, new_state,
            |old_state, project_id FROM logged_actions_page""".stripMargin.query[LoggedActionPage]
    )
  }

  test("LoggedActionUser") {
    check(
      sql"""|SELECT user_id, address, action, subject_id, new_state,
            |old_state FROM logged_actions_user""".stripMargin.query[LoggedActionUser]
    )
  }

  test("LoggedActionOrganization") {
    check(
      sql"""|SELECT user_id, address, action, organization_id, new_state,
            |old_state FROM logged_actions_organization""".stripMargin.query[LoggedActionOrganization]
    )
  }

  test("VersionVisibilityChange") {
    check(sql"""|SELECT created_by, version_id, comment, resolved_at, resolved_by, visibility
                |FROM project_version_visibility_changes""".stripMargin.query[VersionVisibilityChange])
  }

  test("LoggedActionView") {
    /* We can't check this one as all columns in views are nullable
    check(
      sql"""|SELECT id, created_at, user_id, address, action, action_context, action_context_id, new_state, old_state,
            |u_id, u_name, p_id, p_plugin_id, p_slug, p_owner_name, pv_id, pv_version_string, pp_id, pp_slug, s_id,
            |s_name, filter_project, filter_version, filter_page, filter_subject, filter_action
            |FROM v_logged_actions""".stripMargin.query[LoggedActionViewModel])
     */
    check(sql"""SELECT p_id, p_plugin_id, p_slug, p_owner_name FROM v_logged_actions""".query[LoggedProject])
    check(sql"""SELECT pv_id, pv_version_string FROM v_logged_actions""".query[LoggedProjectVersion])
    check(sql"""SELECT pp_id, pp_name, pp_slug FROM v_logged_actions""".query[LoggedProjectPage])
    check(sql"""SELECT s_id, s_name FROM v_logged_actions""".query[LoggedSubject])
  }

  /* We can't check this one as we use String for BIT(N) for the permission field, but doobie doesn't like that
  test("DbRole") {
    check(sql"""SELECT name, category, permission, title, color, is_assignable, rank FROM roles""".query[DbRole])
  }
   */

  test("UserGlobalRoles") {
    check(sql"""SELECT user_id, role_id FROM user_global_roles""".query[(DbRef[User], DbRef[DbRole])])
  }

  test("Job") {
    check(
      sql"""|SELECT last_updated, retry_at, last_error, last_error_descriptor, state, 
            |job_type, job_properties FROM jobs""".stripMargin.query[Job]
    )
  }
}
