import db.DbRef
import models.admin._
import models.api.ProjectApiKey
import models.project._
import models.statistic.{ProjectView, VersionDownload}
import models.user._
import models.user.role.{DbRole, OrganizationUserRole, ProjectUserRole}

import doobie.implicits._
import doobie.postgres.implicits._
import org.junit.runner._
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SchemaSpec extends DbSpec {

  test("Project") {
    check(sql"""|SELECT id, created_at, plugin_id, owner_name, owner_id, name, slug, recommended_version_id,
                |category, description, stars, views, downloads, topic_id, post_id, is_topic_dirty, visibility,
                |last_updated, notes FROM projects""".stripMargin.query[Project])
  }

  test("Project settings") {
    check(sql"""|SELECT id, created_at, project_id, homepage, issues, source, license_name, license_url,
                |forum_sync FROM project_settings""".stripMargin.query[ProjectSettings])
  }

  test("Project watchers") {
    check(sql"""SELECT project_id, user_id FROM project_watchers""".query[(DbRef[Project], DbRef[User])])
  }

  test("Project views") {
    check(sql"""|SELECT id, created_at, project_id, address, cookie,
                |user_id from project_views""".stripMargin.query[ProjectView])
  }

  test("Project stars") {
    check(sql"""SELECT user_id, project_id FROM project_stars""".query[(DbRef[User], DbRef[Project])])
  }

  test("Project log") {
    check(sql"""SELECT id, created_at, project_id FROM project_logs""".query[ProjectLog])
  }

  test("Project log entry") {
    check(sql"""|SELECT id, created_at, log_id, tag, message, occurrences,
                |last_occurrence FROM project_log_entries""".stripMargin.query[ProjectLogEntry])
  }

  test("Page") {
    check(sql"""|SELECT id, created_at, project_id, parent_id, name, slug,
                |is_deletable, contents FROM project_pages""".stripMargin.query[Page])
  }

  test("Channel") {
    check(sql"""|SELECT id, created_at, project_id, name, color,
                |is_non_reviewed FROM project_channels""".stripMargin.query[Channel])
  }

  test("Tag") {
    check(sql"""SELECT id, version_id, name, data, color FROM project_version_tags""".query[VersionTag])
  }

  test("Version") {
    check(sql"""|SELECT id, created_at, project_id, version_string, dependencies, channel_id, file_size, hash,
                |author_id, description, downloads, review_state, reviewer_id, approved_at, visibility, file_name,
                |signature_file_name FROM project_versions
       """.stripMargin.query[Version])
  }

  test("DownloadWarning") {
    check(sql"""|SELECT id, created_at, expiration, token, version_id, address, is_confirmed, download_id
                |FROM project_version_download_warnings""".stripMargin.query[DownloadWarning])
  }

  test("UnsafeDownload") {
    check(sql"""|SELECT id, created_at, user_id, address, download_type
                |FROM project_version_unsafe_downloads""".stripMargin.query[UnsafeDownload])
  }

  test("VersionDownloads") {
    check(sql"""|SELECT id, created_at, version_id, address, cookie, user_id
                |FROM project_version_downloads""".stripMargin.query[VersionDownload])
  }

  test("User") {
    check(
      sql"""|SELECT id, created_at, full_name, name, email, tagline, join_date, read_prompts, pgp_pub_key,
            |last_pgp_pub_key_update, is_locked, language FROM users""".stripMargin.query[User]
    )
  }

  test("Session") {
    check(sql"""SELECT id, created_at, expiration, username, token FROM user_sessions""".query[Session])
  }

  test("SignOn") {
    check(sql"""SELECT id, created_at, nonce, is_completed FROM user_sign_ons""".query[SignOn])
  }

  test("Organization") {
    check(sql"""SELECT id, created_at, name, user_id FROM organizations""".query[Organization])
  }

  test("OrganizationMember") {
    check(sql"""SELECT user_id, organization_id FROM organization_members""".query[(DbRef[User], DbRef[Organization])])
  }

  test("OrganizationRole") {
    check(sql"""|SELECT id, created_at, user_id, organization_id, role_type,
                |is_accepted FROM user_organization_roles""".stripMargin.query[OrganizationUserRole])
  }

  test("ProjectRole") {
    check(sql"""|SELECT id, created_at, user_id, project_id, role_type,
                |is_accepted FROM user_project_roles""".stripMargin.query[ProjectUserRole])
  }

  test("ProjectMember") {
    check(
      sql"""SELECT project_id, user_id FROM project_members""".query[(DbRef[Project], DbRef[User])]
    )
  }

  test("Notifiation") {
    check(sql"""|SELECT id, created_at, user_id, origin_id, notification_type, message_args, action,
                |read FROM notifications""".stripMargin.query[Notification])
  }

  test("Flag") {
    check(sql"""|SELECT id, created_at, project_id, user_id, reason, comment, is_resolved, resolved_at, resolved_by
                |FROM project_flags""".stripMargin.query[Flag])
  }

  test("ProjectApiKey") {
    check(sql"""SELECT id, created_at, project_id, key_type, value FROM project_api_keys""".query[ProjectApiKey])
  }

  test("Review") {
    check(
      sql"""SELECT id, created_at, version_id, user_id, ended_at, comment FROM project_version_reviews""".query[Review]
    )
  }

  test("ProjectVisibilityChange") {
    check(sql"""|SELECT id, created_at, created_by, project_id, comment, resolved_at, resolved_by, visibility 
                |FROM project_visibility_changes""".stripMargin.query[ProjectVisibilityChange])
  }

  test("LoggedAction") {
    check(
      sql"""|SELECT id, created_at, user_id, address, action, action_context, action_context_id, new_state, old_state
            |FROM logged_actions""".stripMargin.query[LoggedActionModel[Any]]
    )
  }

  test("VersionVisibilityChange") {
    check(sql"""|SELECT id, created_at, created_by, version_id, comment, resolved_at, resolved_by, visibility 
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

  test("DbRole") {
    check(sql"""SELECT id, name, category, trust, title, color, is_assignable, rank FROM roles""".query[DbRole])
  }

  test("UserGlobalRoles") {
    check(sql"""SELECT user_id, role_id FROM user_global_roles""".query[(DbRef[User], DbRef[DbRole])])
  }
}
