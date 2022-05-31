<template>
  <div v-if="project && versionObj">
    <!-- Version header -->
    <div class="row">
      <div class="col-md-12 version-header">
        <!-- Title -->
        <div class="clearfix">
          <h1 class="pull-left">
            {{ versionObj.name }}
          </h1>
          <span
            class="channel channel-head"
            :style="{ 'background-color': stability.fromId(versionObj.tags.stability).color }"
          >
            {{ stability.fromId(versionObj.tags.stability).title }}
          </span>
          <span
            v-if="versionObj.tags.release_type"
            class="channel channel-head"
            :style="{ 'background-color': releaseType.fromId(versionObj.tags.release_type).color }"
          >
            {{ releaseType.fromId(versionObj.tags.release_type).title }}
          </span>

          <div class="pull-right">
            <button
              v-if="!editVersion && permissions.includes('edit_tags')"
              class="btn btn-info"
              @click="editVersion = true"
            >
              <FontAwesomeIcon :icon="['fas', 'pencil-alt']" />
            </button>
            <button v-if="editVersion" class="btn btn-info" @click="submitVersion">
              <FontAwesomeIcon :icon="['fas', spinIcon ? 'spinner' : 'paper-plane']" :spin="spinIcon" />
            </button>
            <button v-if="editVersion" class="btn btn-danger" @click="cancelEdit">
              <FontAwesomeIcon :icon="['fas', 'times']" />
            </button>
          </div>
        </div>

        <!-- User info -->
        <p class="user date pull-left">
          <router-link :to="{ name: 'user_projects', params: { user: project.namespace.owner } }">
            <strong>{{ project.namespace.owner }}</strong>
          </router-link>
          released this version on {{ prettifyDate(versionObj.created_at) }}
        </p>

        <!-- Buttons -->

        <div class="pull-right version-actions">
          <div class="version-icons">
            <div>
              <template v-if="isReviewStateChecked">
                <i
                  v-if="
                    permissions.includes('reviewer') &&
                    versionObj.review_state.approved_by &&
                    versionObj.review_state.approved_at
                  "
                  class="minor"
                >
                  <strong> {{ versionObj.review_state.approved_by }} </strong> approved this version on
                  <strong> {{ prettifyDate(versionObj.review_state.approved_at) }} </strong>
                </i>
                <FontAwesomeIcon
                  :icon="['far', 'check-circle']"
                  size="lg"
                  data-toggle="tooltip"
                  data-placement="left"
                  :title="(versionObj.review_state = 'partially_reviewed' ? 'Partially Approved' : 'Approved')"
                />
              </template>
            </div>
          </div>

          <div class="version-buttons pull-right">
            <div>
              <span class="date">{{ formatBytes(versionObj.file_info.size_bytes) }}</span>
            </div>

            <div>
              <a
                v-if="permissions.includes('reviewer')"
                :href="
                  routes.Reviews.showReviews(
                    project.namespace.owner,
                    project.namespace.slug,
                    versionObj.name
                  ).absoluteURL()
                "
                :class="{ btn: true, 'btn-info': isReviewStateChecked, 'btn-success': !isReviewStateChecked }"
              >
                <template v-if="isReviewStateChecked">Review logs</template>
                <FontAwesomeIcon :icon="['fas', 'play']" />
                Start review
              </a>

              <template v-if="permissions.includes('delete_version')">
                <a
                  v-if="versionObj.visibility === 'softDelete'"
                  class="btn btn-danger"
                  disabled
                  data-toggle="tooltip"
                  data-placement="top"
                  title="This version has already been deleted"
                >
                  <FontAwesomeIcon :icon="['fas', 'trash']" />
                  Delete
                </a>
                <a
                  v-else-if="publicVersions === 1"
                  class="btn btn-danger"
                  disabled
                  data-toggle="tooltip"
                  data-placement="top"
                  title="Every project must have at least one version"
                >
                  <FontAwesomeIcon :icon="['fas', 'trash']" />
                  Delete
                </a>
                <button v-else type="button" class="btn btn-danger" data-toggle="modal" data-target="#modal-delete">
                  <FontAwesomeIcon :icon="['fas', 'trash']" />
                  Delete
                </button>
              </template>

              <div class="btn-group btn-download">
                <a
                  :href="
                    routes.project.Versions.download(
                      project.namespace.owner,
                      project.namespace.slug,
                      versionObj.name,
                      null
                    ).absoluteURL()
                  "
                  title="Download the latest recommended version"
                  data-toggle="tooltip"
                  data-placement="bottom"
                  class="btn btn-primary"
                >
                  <FontAwesomeIcon :icon="['fas', 'download']" />
                  Download
                </a>
                <button
                  type="button"
                  class="btn btn-primary dropdown-toggle"
                  data-toggle="dropdown"
                  aria-haspopup="true"
                  aria-expanded="false"
                >
                  <span class="caret" />
                  <span class="sr-only">Toggle Dropdown</span>
                </button>
                <ul class="dropdown-menu dropdown-menu-right">
                  <li>
                    <a
                      :href="
                        routes.project.Versions.download(
                          project.namespace.owner,
                          project.namespace.slug,
                          versionObj.name,
                          null
                        ).absoluteURL()
                      "
                      >Download</a
                    >
                  </li>
                  <li>
                    <a
                      href="#"
                      class="copy-url"
                      :data-clipboard-text="
                        config.app.baseUrl +
                        routes.project.Versions.download(
                          project.namespace.owner,
                          project.namespace.slug,
                          versionObj.name,
                          null
                        ).absoluteURL()
                      "
                      >Copy URL</a
                    >
                  </li>
                </ul>
              </div>

              <template v-if="permissions.includes('see_hidden')">
                <btn-hide
                  :current-visibility="versionObj.visibility"
                  :endpoint="
                    'projects/' +
                    project.namespace.owner +
                    '/' +
                    project.namespace.slug +
                    '/versions/' +
                    versionObj.name +
                    '/visibility'
                  "
                  :callback="updateVisibility"
                />
              </template>

              <div
                v-if="permissions.includes('view_logs')"
                class="dropdown dropdown-menu-right"
                style="display: inline-block;"
              >
                <button
                  id="admin-version-actions"
                  class="btn btn-alert dropdown-toggle"
                  type="button"
                  data-toggle="dropdown"
                  aria-haspopup="true"
                  aria-expanded="true"
                >
                  Admin actions
                  <span class="caret" />
                </button>
                <ul class="dropdown-menu" aria-labelledby="admin-version-actions">
                  <li>
                    <a
                      :href="
                        routes.Application.showLog(null, null, null, versionObj.name, null, null, null).absoluteURL()
                      "
                      >User Action Logs</a
                    >
                  </li>
                  <template v-if="permissions.includes('reviewer')">
                    <li v-if="versionObj.visibility === 'softDelete'">
                      <a href="#" data-toggle="modal" data-target="#modal-restore">Undo delete</a>
                    </li>
                    <li
                      v-if="
                        permissions.includes('hard_delete_version') &&
                        (publicVersions > 1 || versionObj.visibility === 'softDelete')
                      "
                    >
                      <a href="#" data-toggle="modal" data-target="#modal-harddelete" style="color: darkred;">
                        Hard delete
                      </a>
                    </li>
                  </template>
                </ul>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Description -->
    <div class="row version-description">
      <div id="description" class="col-md-8">
        <div class="row">
          <div v-if="!isReviewStateChecked" class="col-md-12">
            <div class="alert-review alert alert-info" role="alert">
              <FontAwesomeIcon :icon="['fas', 'info-circle']" />
              This version has not been reviewed by our moderation staff and may not be safe for download.
            </div>
          </div>
          <div class="col-md-12">
            <editor
              :enabled="permissions.includes('edit_page')"
              :raw="versionDescription ? versionDescription : ''"
              subject="Version"
              :markdown-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/`"
              :markdown-relative-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/`"
              @saved="saveDescription"
            />
          </div>
        </div>
      </div>

      <!-- Dependencies -->
      <div class="col-md-4">
        <div v-if="dependencyObs" class="panel panel-default">
          <div class="panel-heading">
            <h3 class="panel-title">
              Dependencies
            </h3>
          </div>
          <ul class="list-group">
            <li
              v-for="platform in platforms.getPlatforms(dependencyObs.map((d) => d.pluginId))"
              :key="platform.id"
              class="list-group-item"
            >
              <a :href="platform.url">
                <strong>{{ platform.shortName }}</strong>
              </a>
              <p v-if="dependencyObs.filter((d) => d.pluginId === platform.id)[0].version" class="version-string">
                {{ dependencyObs.filter((d) => d.pluginId === platform.id)[0].version }}
              </p>
            </li>

            <li
              v-for="depend in dependencyObs.filter((d) => !platforms.isPlatformDependency(d))"
              :key="depend.pluginId"
              class="list-group-item"
            >
              <router-link
                v-if="depend.project"
                :to="{
                  name: 'project_home',
                  params: {
                    fetchedProject: depend.project,
                    ...depend.project.namespace,
                  },
                }"
              >
                <strong>{{ depend.project.name }}</strong>
              </router-link>
              <div v-else class="minor">
                {{ depend.pluginId }}
                <FontAwesomeIcon
                  :icon="['fas', 'question-circle']"
                  title="This plugin is not available for download on Ore"
                  data-toggle="tooltip"
                  data-placement="right"
                />
              </div>
              <p v-if="depend.version" class="version-string">
                {{ depend.version }}
              </p>
            </li>
          </ul>
        </div>
        <p v-else class="minor text-center">
          <i>This release has no dependencies</i>
        </p>

        <div v-if="editVersion" class="panel panel-default">
          <div class="panel-heading">
            <h3 clasS="panel-title">
              Tags
            </h3>
          </div>
          <ul class="list-group">
            <li class="list-group-item" style="padding-bottom: 20px;">
              <div class="form-inline">
                <label for="setStability">Stability</label>
                <select id="setStability" v-model="editStability" class="form-control pull-right">
                  <option v-for="stabilityObj in stability.values" :key="stabilityObj.id" :value="stabilityObj.id">
                    {{ stabilityObj.title }}
                  </option>
                </select>
              </div>
            </li>
            <li class="list-group-item" style="padding-bottom: 20px;">
              <div class="form-inline">
                <label for="setReleaseType">Release Type</label>
                <select id="setReleaseType" v-model="editReleaseType" class="form-control pull-right">
                  <option :value="null">
                    None
                  </option>
                  <option
                    v-for="releaseTypeObj in releaseType.values"
                    :key="releaseTypeObj.id"
                    :value="releaseTypeObj.id"
                  >
                    {{ releaseTypeObj.title }}
                  </option>
                </select>
              </div>
            </li>
          </ul>
        </div>

        <div v-if="editVersion" class="panel panel-default">
          <div class="panel-heading">
            <h3 class="panel-title">
              Platforms
            </h3>
            <button
              class="btn btn-success"
              aria-label="Add platform"
              @click="editPlatforms.push({ platform: 'spongeapi', platform_version: '' })"
            >
              <FontAwesomeIcon :icon="['fas', 'plus']" />
            </button>
          </div>
          <ul class="list-group">
            <li v-for="(platform, index) in editPlatforms" :key="index" class="list-group-item">
              <div class="form-group">
                <label :for="'platformName-' + platform.platform ? platform.platform : index">Platform: </label>
                <select
                  :id="'platformName-' + platform.platform ? platform.platform : index"
                  v-model="editPlatforms[index].platform"
                  class="form-control"
                >
                  <option v-for="platformObj in platforms.values" :key="platformObj.id" :value="platformObj.id">
                    {{ platformObj.shortName }} ({{ platformObj.id }})
                  </option>
                </select>
              </div>

              <div class="form-group">
                <label :for="'platformVersion-' + platform.platform ? platform.platform : index">Version </label>
                <input
                  :id="'platformName-' + platform.platform ? platform.platform : index"
                  v-model="editPlatforms[index].platform_version"
                  class="form-control"
                  type="text"
                />
              </div>

              <button class="btn btn-danger" aria-label="Remove platform" @click="editPlatforms.splice(index, 1)">
                Remove
              </button>
            </li>
          </ul>
        </div>

        <div v-if="editVersion && permissions.includes('edit_admin_settings')" class="panel panel-default">
          <div class="panel-heading">
            <h3 clasS="panel-title">
              Discourse settings
            </h3>
          </div>
          <ul class="list-group">
            <li class="list-group-item" style="padding-bottom: 20px;">
              <div class="form-inline">
                <label for="setPostId">Post id</label>
                <input id="setPostId" v-model="discoursePostId" type="number" class="form-control pull-right" />
              </div>
            </li>
            <li class="list-group-item" style="padding-bottom: 20px;">
              <div class="form-inline">
                <label for="updatePost">Update post</label>
                <input id="updatePost" v-model="discourseSendUpdate" type="checkbox" class="form-control pull-right" />
              </div>
            </li>
            <li class="list-group-item">
              <button
                id="btn-discourse-update"
                data-toggle="modal"
                data-target="#modal-discourse-update"
                class="btn btn-warning"
              >
                Update post
              </button>
            </li>
          </ul>
        </div>
      </div>
    </div>

    <modal
      ref="discourseUpdateModal"
      name="discourse-update"
      title="Update Discourse settings"
      button-label="Update post"
      :on-submit="updateDiscoursePost"
    >
      Make sure the new values are correct. Wrong values here can have negative side effects.
      <dl>
        <dt>Post id:</dt>
        <dd>{{ discoursePostId }}</dd>
      </dl>
    </modal>

    <modal
      v-if="permissions.includes('delete_version') && publicVersions !== 1"
      ref="deleteModal"
      name="delete"
      title="Delete version"
      button-label="Delete"
      :on-submit="() => setVisibility('softDelete')"
      :on-close="() => (modalComment = '')"
    >
      Are you sure you want to delete this version? This action cannot be undone. You will not be able to reuse this
      version string later. Please explain why you want to delete it.
      <textarea v-model="modalComment" name="comment" class="textarea-delete-comment form-control" rows="3" />
    </modal>

    <modal
      v-if="permissions.includes('reviewer') && versionObj.visibility === 'softDelete'"
      ref="restoreModal"
      name="restore"
      title="Restore deleted"
      button-label="Restore"
      :on-submit="() => setVisibility('public')"
      :on-close="() => (modalComment = '')"
    >
      <textarea v-model="modalComment" name="comment" class="textarea-delete-comment form-control" rows="3" />
    </modal>

    <modal
      v-if="permissions.includes('reviewer') && permissions.includes('hard_delete_version')"
      ref="hardDeleteModal"
      name="harddelete"
      title="Hard delete"
      button-label="Hard delete"
      :on-close="() => (modalComment = '')"
      :on-submit="hardDeleteVersion"
    >
      <textarea v-model="modalComment" name="comment" class="textarea-delete-comment form-control" rows="3" />
    </modal>
  </div>
  <div v-else />
</template>

<script>
import { mapState } from 'vuex'
import NProgress from 'nprogress'
import uniqWith from 'lodash/uniqWith'
import isEqual from 'lodash/isEqual'
import ClipboardJS from 'clipboard'
import Editor from '../../components/Editor'
import { API } from '../../api'
import { Platform, ReleaseType, Stability } from '../../enums'
import config from '../../config.json5'
import BtnHide from '../../components/BtnHide'
import { clearFromDefaults, genericError, notFound } from '../../utils'
import Modal from '../../components/Modal'

const clipboardManager = new ClipboardJS('.copy-url')
clipboardManager.on('success', function (e) {
  const element = $('.btn-download')
    .tooltip({ title: 'Copied!', placement: 'bottom', trigger: 'manual' })
    .tooltip('show')
  setTimeout(function () {
    element.tooltip('destroy')
  }, 2200)
})

export default {
  components: {
    Modal,
    Editor,
    BtnHide,
  },
  props: {
    version: {
      type: String,
      required: true,
    },
    fetchedVersionObj: {
      type: Object,
      default: null,
    },
  },
  data() {
    return {
      versionObj: null,
      versionDescription: null,
      dependencyObs: [],
      modalComment: '',
      editVersion: false,
      editStability: 'stable',
      editReleaseType: null,
      editPlatforms: [],
      spinIcon: false,
      discoursePostId: null,
      discourseSendUpdate: true,
    }
  },
  computed: {
    routes() {
      return jsRoutes.controllers
    },
    isReviewStateChecked() {
      return this.versionObj.review_state === 'partially_reviewed' || this.versionObj.review_state === 'reviewed'
    },
    publicVersions() {
      return 10 // TODO
    },
    config() {
      return config
    },
    platforms() {
      return Platform
    },
    stability() {
      return Stability
    },
    releaseType() {
      return ReleaseType
    },
    ...mapState('project', ['project', 'permissions']),
  },
  watch: {
    $route: 'updateVersion',
    project(val, oldVal) {
      if (!oldVal || val.plugin_id !== oldVal.plugin_id) {
        this.updateVersion()
      }
    },
    versionObj() {
      this.resetEdit()
    },
  },
  created() {
    if (this.fetchedVersionObj && this.fetchedVersionObj.name === this.version) {
      this.versionObj = this.fetchedVersionObj
    }

    if (this.project) {
      this.updateVersion()
    }
  },
  methods: {
    updateVersion() {
      let futureVersion

      if (!this.versionObj || this.versionObj.name !== this.version) {
        NProgress.start()

        futureVersion = API.versionRequest(this.project.namespace, this.version)
      } else {
        futureVersion = Promise.resolve(this.versionObj)
      }

      futureVersion
        .then((v) => {
          NProgress.done()
          this.versionObj = v

          for (const dependency of v.dependencies) {
            const depObj = { pluginId: dependency.plugin_id, version: dependency.version, project: null }

            if (Platform.isPlatformDependency(depObj)) {
              this.dependencyObs.push(depObj)
            } else {
              API.request('projects/' + dependency.plugin_id)
                .then((d) => {
                  depObj.project = d
                  this.dependencyObs.push(depObj)
                })
                .catch((error) => {
                  if (error === 404) {
                    this.dependencyObs.push(depObj)
                  } else {
                    this.$store.commit({
                      type: 'addAlert',
                      level: 'warning',
                      message: 'An error occoured when getting the project for the dependency ' + dependency.plugin_id,
                    })
                  }
                })
            }
          }
        })
        .catch((error) => {
          this.versionObj = null

          if (error === 404) {
            notFound(this)
          } else {
            genericError(this, 'An error occoured when getting the version')
          }
        })

      API.versionRequest(this.project.namespace, this.version, 'changelog').then((o) => {
        this.versionDescription = o.changelog
      })
    },
    prettifyDate(date) {
      return new Date(date).toLocaleDateString('default', { year: 'numeric', month: 'long', day: 'numeric' })
    },
    // https://stackoverflow.com/a/18650828/7207457
    formatBytes(bytes, decimals = 2) {
      if (bytes === 0) {
        return '0 Bytes'
      }

      const k = 1024
      const dm = decimals < 0 ? 0 : decimals
      const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']

      const i = Math.floor(Math.log(bytes) / Math.log(k))

      return parseFloat((bytes / k ** i).toFixed(dm)) + ' ' + sizes[i]
    },
    saveDescription(newDescription) {
      API.versionRequest(this.project.namespace, this.version, 'changelog', 'PUT', {
        changelog: newDescription,
      }).then((res) => {
        this.versionDescription = newDescription
        this.$store.commit({
          type: 'replaceAlert',
          level: 'success',
          message: `[${new Date().toLocaleTimeString()}] Updated the changelog of ${this.version}`,
          tag: 'updateVersionDescription',
        })
      })
    },
    setVisibility(visibility) {
      API.versionRequest(this.project.namespace, this.version, 'visibility', 'POST', {
        visibility,
        comment: this.modalComment,
      }).then((res) => {
        this.versionObj.visibility = visibility

        // eslint-disable-next-line no-unused-expressions
        this.$refs?.restoreModal?.hide()
        // eslint-disable-next-line no-unused-expressions
        this.$refs?.deleteModal?.hide()

        if (!this.permissions.includes('see_hidden')) {
          this.$router.push({ name: 'versions' })
        } else {
          this.updateVisibility()
        }
      })
    },
    updateVisibility(visibility) {
      this.versionObj.visibility = visibility
      this.$store.commit({
        type: 'replaceAlert',
        level: 'success',
        message: `[${new Date().toLocaleTimeString()}] Set the visibility of ${this.version} to ${visibility}`,
        tag: 'setVisiblity',
      })
    },
    updateDiscoursePost() {
      API.versionRequest(this.project.namespace, this.version, 'external/_discourse', 'POST', {
        post_id: this.discoursePostId === '' ? null : this.discoursePostId,
        update_post: this.discourseSendUpdate,
      }).then(() => {
        this.$refs.discourseUpdateModal.hide()
        this.$store.commit({
          type: 'addAlert',
          level: 'success',
          message: 'Updated discourse settings',
        })
      })
    },
    hardDeleteVersion() {
      API.versionRequest(this.project.namespace, this.version, '', 'DELETE').then((res) => {
        this.$refs.hardDeleteModal.hide()
        this.$store.commit({
          type: 'replaceAlert',
          level: 'success',
          message: `Hard deleted ${this.version}`,
          tag: 'hardDeleteVersion',
        })

        this.$router.push({ name: 'versions' })
      })
    },
    simplifyPlatform(platform) {
      return {
        platform: platform.platform,
        platform_version: platform.platform_version,
      }
    },
    resetEdit() {
      this.editStability = this.versionObj.tags.stability
      this.editReleaseType = this.versionObj.tags.release_type
      this.editPlatforms = this.versionObj.tags.platforms.map(this.simplifyPlatform)
      this.discoursePostId = this.versionObj.external.discourse.post_id
      this.discourseSendUpdate = true
    },
    cancelEdit() {
      this.editVersion = false
      this.resetEdit()
    },
    submitVersion() {
      const current = {
        stability: this.versionObj.tags.stability,
        release_type: this.versionObj.tags.release_type,
      }

      let patchVersion = {
        stability: this.editStability,
        release_type: this.editReleaseType,
        platforms: uniqWith(this.editPlatforms, isEqual).map((obj) =>
          !obj.platform_version.length
            ? {
                ...obj,
                platform_version: null,
              }
            : obj
        ),
      }

      const currentPlatforms = this.versionObj.tags.platforms

      function allSamePlatforms() {
        for (const platform of patchVersion.platforms) {
          if (
            !currentPlatforms.some(
              (p) => p.platform === platform.platform && p.platform_version === platform.platform_version
            )
          ) {
            return false
          }
        }

        return true
      }

      if (currentPlatforms.length === patchVersion.platforms.length && allSamePlatforms()) {
        delete patchVersion.platforms
      }

      patchVersion = clearFromDefaults(patchVersion, current)

      this.spinIcon = true
      if (Object.entries(patchVersion).length) {
        API.versionRequest(this.project.namespace, this.version, '', 'PATCH', patchVersion).then((res) => {
          this.spinIcon = false
          this.editVersion = false
          this.versionObj = res
          this.resetEdit()
        })
      } else {
        setTimeout(() => {
          this.spinIcon = false
          this.editVersion = false
          this.resetEdit()
        }, 150)
      }
    },
  },
}
</script>
