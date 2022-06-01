<template>
  <div class="project-header-container">
    <div v-if="project && project.visibility !== 'public'" class="row">
      <div class="col-xs-12">
        <div class="alert alert-danger" role="alert" style="margin: 0.2em 0 0 0;">
          <span v-if="project.visibility === 'new'">
            This project is new, and will not be shown to others until a version has been uploaded. If a version is not
            uploaded over a longer time the project will be deleted.
          </span>
          <span v-else-if="project.visibility === 'needsChanges'">
            <a
              v-if="permissions.includes(permission.EditPage)"
              class="btn btn-success pull-right"
              :href="fullSlug + '/manage/sendforapproval'"
              >Send for approval</a
            >
            <strong>This project requires changes:</strong>
            <!-- eslint-disable-next-line vue/no-v-html -->
            <span v-html="renderVisibilityChange('Unknown')" />
          </span>
          <span v-else-if="project.visibility === 'needsApproval'">
            You have sent the project for review
          </span>
          <span v-else-if="project.visibility === 'softDelete'">
            <!-- eslint-disable-next-line vue/no-v-html -->
            Project deleted by {{ projectData.lastVisibilityChangeUser }} <span v-html="renderVisibilityChange('')" />
          </span>
        </div>
      </div>
    </div>

    <div class="row">
      <div class="col-md-6">
        <div v-if="project" class="project-header">
          <div class="project-path">
            <router-link :to="{ name: 'user_projects', params: { user: project.namespace.owner } }">
              {{ project.namespace.owner }}
            </router-link>
            /
            <router-link v-slot="{ href, navigate }" :to="{ name: 'project_home' }">
              <a class="project-name" :href="href" @click="navigate">{{ project.name }}</a>
            </router-link>
          </div>
          <div>
            <i class="minor" :title="project.summary"> {{ project.summary }} </i>
          </div>
        </div>
      </div>
      <div class="col-md-6">
        <div v-if="!noButtons" class="pull-right project-controls">
          <span v-if="reported" class="flag-msg">
            <FontAwesomeIcon :icon="['fas', 'thumbs-up']" />
            Flag submitted for review
          </span>
          <span v-if="flagError" class="flag-msg">
            <FontAwesomeIcon :icon="['fas', 'thumbs-down']" />
            Error when submitting flag for review:
            {{ flagError }}
          </span>

          <template v-if="project && project.visibility !== 'softDelete'">
            <template v-if="!isMember && currentUser">
              <div class="btn-group" role="group" aria-label="Stars">
                <button class="btn btn-default btn-star" @click="toggleStarred">
                  <FontAwesomeIcon :icon="starredIcon" />
                  <span :class="{ starred: project.user_actions.starred }">
                    {{ project.user_actions.starred ? 'Unstar' : 'Star' }}
                  </span>
                </button>
                <a
                  :href="
                    routes.project.Projects.showStargazers(
                      project.namespace.owner,
                      project.namespace.slug,
                      null
                    ).absoluteURL()
                  "
                  class="btn btn-default"
                >
                  {{ formatStats(project.stats.stars) }}
                </a>
              </div>

              <div class="btn-group" role="group" aria-label="Watchers">
                <button class="btn btn-default btn-watch" @click="toggleWatching">
                  <FontAwesomeIcon :icon="watchingIcon" />
                  <span :class="{ watching: project.user_actions.watching }">
                    {{ project.user_actions.watching ? 'Unwatch' : 'Watch' }}
                  </span>
                </button>
                <a
                  :href="
                    routes.project.Projects.showWatchers(
                      project.namespace.owner,
                      project.namespace.slug,
                      null
                    ).absoluteURL()
                  "
                  class="btn btn-default"
                >
                  {{ formatStats(project.stats.watchers) }}
                </a>
              </div>
            </template>
            <template v-else>
              <span class="minor stat-static">
                <FontAwesomeIcon :icon="starredIcon" />
                {{ project.stats.stars }}
              </span>
              <span class="minor stat-static">
                <FontAwesomeIcon :icon="watchingIcon" />
                {{ project.stats.watchers }}
              </span>
            </template>

            <button data-toggle="modal" data-target="#modal-flag" class="btn btn-default">
              <FontAwesomeIcon :icon="['fas', 'flag']" /> Flag
            </button>

            <div id="modal-flag" class="modal fade" tabindex="-1" role="dialog" aria-labelledby="label-flag">
              <div class="modal-dialog" role="document">
                <div class="modal-content">
                  <div class="modal-header">
                    <button type="button" class="close" data-dismiss="modal" aria-label="Close">
                      <span aria-hidden="true">&times;</span>
                    </button>
                    <h4 id="label-flag" class="modal-title">
                      Flag project
                    </h4>
                  </div>
                  <form
                    id="flagForm"
                    :action="
                      routes.project.Projects.flag(project.namespace.owner, project.namespace.slug).absoluteURL()
                    "
                    method="post"
                  >
                    <CSRFField />
                    <div class="modal-body">
                      <ul class="list-group list-flags">
                        <li class="list-group-item">
                          <span>Select a reason</span>
                          <span class="pull-right">
                            <input
                              v-model="reportReason"
                              required
                              disabled
                              hidden
                              type="radio"
                              value="none-selected"
                              name="flag-reason"
                            />
                          </span>
                        </li>
                        <li v-for="reason in flagReason.values" :key="reason.value" class="list-group-item">
                          <span>{{ reason.title }}</span>
                          <span class="pull-right">
                            <input
                              v-model="reportReason"
                              required
                              type="radio"
                              :value="reason.value"
                              name="flag-reason"
                            />
                          </span>
                        </li>
                      </ul>
                      <input
                        v-model="reportComment"
                        class="form-control"
                        name="comment"
                        type="text"
                        maxlength="255"
                        required
                        placeholder="Comment&hellip;"
                      />
                    </div>
                    <div class="modal-footer">
                      <button type="button" class="btn btn-default" data-dismiss="modal" @click="clearFlag">
                        Close
                      </button>
                      <button type="button" class="btn btn-primary" @click="sendFlag">
                        Flag
                      </button>
                    </div>
                  </form>
                </div>
              </div>
            </div>
          </template>

          <template
            v-if="permissions.includes(permission.ModNotesAndFlags) || permissions.includes(permission.ViewLogs)"
          >
            <button
              id="admin-actions"
              class="btn btn-alert dropdown-toggle"
              type="button"
              data-toggle="dropdown"
              aria-haspopup="true"
              aria-expanded="true"
            >
              Admin actions
              <span class="caret" />
            </button>
            <ul v-if="project" class="dropdown-menu" aria-labelledby="admin-actions">
              <li v-if="permissions.includes(permission.ModNotesAndFlags)">
                <a
                  :href="
                    routes.project.Projects.showFlags(project.namespace.owner, project.namespace.slug).absoluteURL()
                  "
                >
                  Flag history ({{ projectData.flagCount }})
                </a>
              </li>
              <li v-if="permissions.includes(permission.ModNotesAndFlags)">
                <a
                  :href="
                    routes.project.Projects.showNotes(project.namespace.owner, project.namespace.slug).absoluteURL()
                  "
                >
                  Staff notes ({{ projectData.noteCount }})
                </a>
              </li>
              <li v-if="permissions.includes(permission.ViewLogs)">
                <a
                  :href="
                    routes.Application.showLog(null, null, project.plugin_id, null, null, null, null).absoluteURL()
                  "
                >
                  User Action Logs
                </a>
              </li>
              <li>
                <a :href="'https://forums.spongepowered.org/users/' + project.namespace.owner">
                  Owner on forum <FontAwesomeIcon :icon="['fas', 'external-link-alt']" aria-hidden="true" />
                </a>
              </li>
            </ul>
          </template>
        </div>
      </div>
    </div>

    <div class="row row-nav">
      <div class="col-md-12">
        <div class="navbar navbar-default project-navbar pull-left">
          <div class="navbar-inner">
            <ul class="nav navbar-nav">
              <router-link v-slot="{ href, navigate, isExactActive }" :to="{ name: 'project_home' }">
                <li :class="[isExactActive && 'active']">
                  <a :href="href" @click="navigate"><FontAwesomeIcon :icon="['fas', 'book']" /> Docs</a>
                </li>
              </router-link>

              <router-link v-slot="{ href, navigate, isActive }" :to="{ name: 'versions' }">
                <li :class="[isActive && 'active']">
                  <a :href="href" @click="navigate"><FontAwesomeIcon :icon="['fas', 'download']" /> Versions</a>
                </li>
              </router-link>

              <router-link
                v-if="project && project.external.discourse.topic_id"
                v-slot="{ href, navigate, isActive }"
                :to="{ name: 'discussion' }"
              >
                <li :class="[isActive && 'active']">
                  <a :href="href" @click="navigate"><FontAwesomeIcon :icon="['fas', 'users']" /> Discuss</a>
                </li>
              </router-link>

              <router-link
                v-if="permissions.includes(permission.EditSubjectSettings)"
                v-slot="{ href, navigate, isActive }"
                :to="{ name: 'settings' }"
              >
                <li :class="[isActive && 'active']">
                  <a :href="href" @click="navigate"><FontAwesomeIcon :icon="['fas', 'cog']" /> Settings</a>
                </li>
              </router-link>

              <li v-if="project && project.settings.homepage" id="homepage">
                <a
                  :title="project.settings.homepage"
                  target="_blank"
                  rel="noopener"
                  :href="routes.Application.linkOut(project.settings.homepage).absoluteURL()"
                >
                  <FontAwesomeIcon :icon="['fas', 'home']" /> Homepage
                  <FontAwesomeIcon :icon="['fas', 'external-link-alt']"
                /></a>
              </li>

              <li v-if="project && project.settings.issues" id="issues">
                <a
                  :title="project.settings.issues"
                  target="_blank"
                  rel="noopener"
                  :href="routes.Application.linkOut(project.settings.issues).absoluteURL()"
                >
                  <FontAwesomeIcon :icon="['fas', 'bug']" /> Issues
                  <FontAwesomeIcon :icon="['fas', 'external-link-alt']"
                /></a>
              </li>

              <li v-if="project && project.settings.sources" id="source">
                <a
                  :title="project.settings.sources"
                  target="_blank"
                  rel="noopener"
                  :href="routes.Application.linkOut(project.settings.sources).absoluteURL()"
                >
                  <FontAwesomeIcon :icon="['fas', 'code']" /> Source
                  <FontAwesomeIcon :icon="['fas', 'external-link-alt']" />
                </a>
              </li>

              <li v-if="project && project.settings.support" id="support">
                <a
                  :title="project.settings.support"
                  target="_blank"
                  rel="noopener"
                  :href="routes.Application.linkOut(project.settings.support).absoluteURL()"
                >
                  <FontAwesomeIcon :icon="['fas', 'question-circle']" /> Support
                  <FontAwesomeIcon :icon="['fas', 'external-link-alt']" />
                </a>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import markdownIt from 'markdown-it'
import markdownItAnchor from 'markdown-it-anchor'
//import markdownItWikilinks from 'markdown-it-wikilinks' //TODO: Find/make an replacement
import markdownItTaskLists from 'markdown-it-task-lists'
import { mapState } from 'vuex'
import { API } from '../api'
import { FlagReason, Permission } from '../enums'
import { genericError, numberWithCommas } from '../utils'
import CSRFField from './CSRFField'

const md = markdownIt({
  linkify: true,
  typographer: true,
})
  .use(markdownItAnchor)
  //.use(markdownItWikilinks({ relativeBaseURL: location.pathname + '/pages/', uriSuffix: '' }))
  .use(markdownItTaskLists)

export default {
  components: {
    CSRFField,
  },
  props: {
    noButtons: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      reported: false,
      flagError: null,
      reportReason: 'none-selected',
      reportComment: '',
      projectData: {
        flagCount: -1,
        noteCount: -1,
      },
    }
  },
  computed: {
    fullSlug() {
      return this.project.namespace.owner + '/' + this.project.slug
    },
    routes() {
      return jsRoutes.controllers
    },
    starredIcon() {
      return [this.project.user_actions.starred ? 'fas' : 'far', 'star']
    },
    watchingIcon() {
      return ['fas', this.project.user_actions.watching ? 'eye-slash' : 'eye']
    },
    isMember() {
      return (
        this.currentUser &&
        this.members.filter((m) => {
          return m.user === this.currentUser.name
        }).length > 0
      )
    },
    permission() {
      return Permission
    },
    flagReason() {
      return FlagReason
    },
    ...mapState('global', {
      currentUser: 'currentUser',
    }),
    ...mapState('project', ['project', 'permissions', 'members']),
  },
  watch: {
    project(val) {
      API.projectRequest(val.namespace, '_projectData').then((res) => {
        this.projectData = res
      })
    },
  },
  methods: {
    renderVisibilityChange(orElse) {
      if (this.projectData && this.projectData.lastVisibilityChange) {
        return md.render(this.projectData.lastVisibilityChange.comment)
      } else {
        return orElse
      }
    },
    clearFlag() {
      this.reportReason = 'none-selected'
      this.reportComment = ''
    },
    sendFlag() {
      $.ajax({
        type: 'POST',
        url: this.routes.project.Projects.flag(this.project.namespace.owner, this.project.namespace.slug).absoluteURL(),
        data: $('#flagForm').serialize(),
      })
        .done((res) => {
          this.reported = true
          $('#modal-flag').modal('toggle')
          this.clearFlag()
        })
        .fail((xhr) => {
          $('#modal-flag').modal('toggle')
          this.flagError = xhr.responseText
        })
    },
    toggleStarred() {
      $.ajax({
        type: 'POST',
        url: this.routes.project.Projects.toggleStarred(
          this.project.namespace.owner,
          this.project.namespace.slug
        ).absoluteURL(),
        data: {
          csrfToken: window.csrf,
        },
      })
        .done((res) => {
          this.$store.commit('project/toggleStarred')
        })
        .fail((xhr) => {
          genericError(this, 'An error occoured when toggling starred')
        })
    },
    toggleWatching() {
      const newWatching = !this.project.user_actions.watching
      $.ajax({
        type: 'POST',
        url: this.routes.project.Projects.setWatching(
          this.project.namespace.owner,
          this.project.namespace.slug,
          newWatching
        ).absoluteURL(),
        data: {
          csrfToken: window.csrf,
        },
      })
        .done((res) => {
          this.$store.commit({
            type: 'project/setWatching',
            watching: newWatching,
          })
        })
        .fail((xhr) => {
          genericError(this, 'An error occoured when toggling watching')
        })
    },
    formatStats(number) {
      return numberWithCommas(number)
    },
  },
}
</script>
