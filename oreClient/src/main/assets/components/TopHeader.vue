<template>
  <nav id="topbar" class="navbar-main navbar-inverse">
    <div class="container">
      <!-- Left navbar -->
      <div id="sp-logo-container">
        <router-link class="logo" :to="{ name: 'home' }">
          <img src="../images/spongie-mark.svg" alt="Sponge logo" />
          <span>Sponge</span>
          <FontAwesomeIcon :icon="['fas', 'chevron-down']" fixed-width />
        </router-link>

        <div id="sp-logo-menu">
          <ul id="sp-logo-dropdown">
            <li>
              <a href="https://www.spongepowered.org">
                <FontAwesomeIcon :icon="['fas', 'home']" fixed-width /> Homepage
              </a>
            </li>
            <li>
              <a href="https://forums.spongepowered.org">
                <FontAwesomeIcon :icon="['fas', 'comments']" fixed-width /> Forums
              </a>
            </li>
            <li>
              <a href="https://github.com/SpongePowered">
                <FontAwesomeIcon :icon="['fas', 'code']" fixed-width /> Code
              </a>
            </li>
            <li>
              <a href="https://docs.spongepowered.org">
                <FontAwesomeIcon :icon="['fas', 'book']" fixed-width /> Docs
              </a>
            </li>
            <li>
              <a href="https://jd.spongepowered.org">
                <FontAwesomeIcon :icon="['fas', 'graduation-cap']" fixed-width /> Javadocs
              </a>
            </li>
            <li class="active">
              <router-link :to="{ name: 'home' }">
                <img src="../images/ore-nav.svg" alt="" class="fa-fw ore-nav" />Plugins (Ore)
              </router-link>
            </li>
            <li>
              <a href="https://www.spongepowered.org/downloads">
                <FontAwesomeIcon :icon="['fas', 'download']" fixed-width /> Downloads
              </a>
            </li>
            <li>
              <a href="https://www.spongepowered.org/chat">
                <FontAwesomeIcon :icon="['fas', 'comment']" fixed-width /> Chat
              </a>
            </li>
          </ul>
        </div>
      </div>

      <ul class="nav navbar-nav navbar-collapse collapse navbar-right">
        <li v-if="currentUser" class="dropdown nav-icon new-controls">
          <a href="#" class="drop-down-toggle" data-toggle="dropdown">
            <FontAwesomeIcon class="icon" :icon="['fas', 'plus']" fixed-width />
            <span class="caret" />
          </a>
          <ul class="user-dropdown dropdown-menu" aria-label="Create new&hellip;">
            <li>
              <router-link :to="{ name: 'new_project' }">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'book']" fixed-width />
                <span>New project</span>
              </router-link>
              <a :href="routes.Organizations.showCreator().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'users']" fixed-width />
                <span>New organization</span>
              </a>
            </li>
          </ul>
        </li>

        <li class="nav-icon authors-icon" data-toggle="tooltip" data-placement="bottom" title="View project creators.">
          <a :href="routes.Users.showAuthors(null, null).absoluteURL()">
            <FontAwesomeIcon class="icon" :icon="['fas', 'users']" />
          </a>
        </li>

        <li
          v-if="permissions.includes('is_staff')"
          class="nav-icon staff-icon"
          data-toggle="tooltip"
          data-placement="bottom"
          title="View Sponge staff."
        >
          <a :href="routes.Users.showStaff(null, null).absoluteURL()">
            <FontAwesomeIcon class="icon" :icon="['fas', 'user-tie']" />
          </a>
        </li>

        <li v-if="currentUser" class="dropdown user-controls nav-icon">
          <a href="#" class="drop-down-toggle user-toggle" data-toggle="dropdown">
            <span v-if="headerData && headerData.hasNotice" class="unread" />
            <img
              height="32"
              width="32"
              class="user-avatar"
              :src="avatarUrl(currentUser.name)"
              :alt="currentUser.name"
            />
            <span class="caret" />
          </a>
          <ul class="user-dropdown dropdown-menu" aria-label="dropdownMenu1">
            <li v-if="currentUser">
              <router-link :to="{ name: 'user_projects', params: { user: currentUser.name } }">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'user']" />
                <span>{{ currentUser.name }}</span>
              </router-link>
            </li>

            <li v-if="currentUser">
              <a :href="routes.Users.showNotifications(null, null).absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'bell']" />
                <span>
                  Notifications
                  <span v-if="headerData && headerData.hasUnreadNotifications" class="unread" />
                </span>
              </a>
            </li>

            <li v-if="permissions.includes('mod_notes_and_flags')">
              <a :href="routes.Application.showFlags().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'flag']" />
                <span>
                  Flags
                  <span v-if="headerData && headerData.unresolvedFlags" class="unread" />
                </span>
              </a>
            </li>

            <li v-if="permissions.includes('mod_notes_and_flags')">
              <a :href="routes.Application.showProjectVisibility().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'thumbs-up']" />
                <span>
                  Project approvals
                  <span v-if="headerData && headerData.hasProjectApprovals" class="unread" />
                </span>
              </a>
            </li>

            <li v-if="permissions.includes('reviewer')">
              <a :href="routes.Application.showQueue().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['far', 'thumbs-up']" />
                <span>
                  Version approvals
                  <span v-if="headerData && headerData.hasReviewQueue" class="unread" />
                </span>
              </a>
            </li>

            <li v-if="permissions.includes('view_stats')">
              <a :href="routes.Application.showStats(null, null).absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'chart-area']" />
                <span>Stats</span>
              </a>
            </li>

            <li v-if="permissions.includes('view_health')">
              <a :href="routes.Application.showHealth().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'heartbeat']" />
                <span>Ore Health Report</span>
              </a>
            </li>

            <li v-if="permissions.includes('view_logs')">
              <a :href="routes.Application.showLog(null, null, null, null, null, null, null).absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'list']" />
                <span>User action log</span>
              </a>
            </li>

            <li role="separator" class="divider" />
            <li>
              <a :href="routes.Users.logOut().absoluteURL()">
                <FontAwesomeIcon class="mr-1" :icon="['fas', 'sign-out-alt']" />
                <span>Sign out</span>
              </a>
            </li>
          </ul>
        </li>
        <li v-if="!currentUser">
          <div class="btn-group-login">
            <a :href="routes.Users.signUp().absoluteURL()" class="btn btn-primary navbar-btn">Sign up</a>
            <a :href="routes.Users.logIn(null, null, currentPath).absoluteURL()" class="btn btn-primary navbar-btn">
              Log in
            </a>
          </div>
        </li>
      </ul>
    </div>
  </nav>
</template>

<script>
import { mapState } from 'vuex'
import { avatarUrl } from '../utils'

export default {
  computed: {
    routes() {
      return jsRoutes.controllers
    },
    currentPath() {
      return window.location.pathname
    },
    ...mapState('global', ['currentUser', 'permissions', 'headerData']),
  },
  methods: {
    avatarUrl,
  },
}
</script>
