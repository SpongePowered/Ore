<template>
  <div class="row user-header">
    <div class="header-body">
      <span class="user-badge">
        <icon
          v-if="user"
          :src="avatarUrl(user.name)"
          :name="user.name"
          :extra-classes="'user-avatar-md' + (canEditOrgSettings ? 'organization-avatar' : '')"
        />

        <template v-if="user && canEditOrgSettings">
          <div class="edit-avatar" style="display: none">
            <a :href="routes.Organizations.updateAvatar(user.name).absoluteURL()"
              ><i class="fas fa-edit" /> Edit avatar</a
            >
          </div>

          <prompt
            v-if="!headerData.readPrompts.includes(prompts.ChangeAvatar.id)"
            :prompt="prompts.ChangeAvatar"
            id-class="popover-avatar"
          />
        </template>

        <span class="user-title">
          <h1 v-if="user" class="username">
            {{ user.name }}

            <template v-if="isCurrentUser && !orga">
              <a class="user-settings" :href="config.security.api.url + '/accounts/settings'">
                <i class="fas fa-cog" data-toggle="tooltip" data-placement="top" title="Settings" />
              </a>

              <a class="action-api" :href="routes.Users.editApiKeys(user.name).absoluteURL()">
                <i class="fas fa-key" data-toggle="tooltip" data-placement="top" title="API Keys" />
              </a>
            </template>

            <a
              v-if="permissions.includes('mod_notes_and_flags') || permissions.includes('reviewer')"
              class="user-settings"
              :href="routes.Application.showActivities(user.name).absoluteURL()"
            >
              <i class="fas fa-calendar" data-toggle="tooltip" data-placement="top" title="Activity" />
            </a>

            <a
              v-if="permissions.includes('edit_all_user_settings')"
              class="user-settings"
              :href="routes.Application.userAdmin(user.name).absoluteURL()"
            >
              <i class="fas fa-wrench" data-toggle="tooltip" data-placement="top" title="User Admin" />
            </a>
          </h1>

          <div class="user-tag">
            <i v-if="user && user.tagline" class="minor">{{ user.tagline }}</i>
            <i v-else-if="isCurrentUser || canEditOrgSettings" class="minor"> Add a tagline </i>

            <a v-if="isCurrentUser || canEditOrgSettings" href="#" data-toggle="modal" data-target="#modal-tagline">
              <i class="fas fa-edit" />
            </a>
          </div>
        </span>
      </span>

      <!-- Roles -->
      <ul v-if="user" class="user-roles">
        <li
          v-for="role in user.roles"
          :key="role.name"
          class="user-role channel"
          :style="{ 'background-color': role.color }"
        >
          {{ role.title }}
        </li>
      </ul>

      <div v-if="user" class="user-info">
        <i class="minor">{{ user.project_count }}&nbsp;{{ user.project_count === 1 ? 'project' : 'projects' }}</i
        ><br />
        <i class="minor">
          A member since {{ user.join_date ? prettifyDate(user.join_date) : prettifyDate(user.created_at) }} </i
        ><br />
        <a :href="'https://forums.spongepowered.org/users/' + user.name">
          View on forums <i class="fas fa-external-link-alt" />
        </a>
      </div>

      <modal
        ref="taglineModal"
        name="tagline"
        title="Edit tagline"
        button-label="Save"
        :on-close="resetTagline"
        :on-submit="updateTagline"
      >
        <div class="setting setting-no-border">
          <div class="setting-description">
            <h4>Tagline</h4>
            <p>Add a short tagline to let people know what you're about!</p>
          </div>
          <input
            id="tagline"
            v-model="editTagline"
            class="form-control"
            type="text"
            name="tagline"
            :maxlength="config.ore.users.maxTaglineLen"
          />
        </div>
        <div class="clearfix" />
      </modal>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { Role, Prompt as PromptEnum } from '../enums'
import { avatarUrl, genericError } from '../utils'
import config from '../config.json5'
import Prompt from './Prompt'
import Icon from './Icon'
import Modal from './Modal'

export default {
  components: { Modal, Icon, Prompt },
  data() {
    return {
      editTagline: this.currentUser ? (this.currentUser.tagline ? this.currentUser.tagline : '') : '',
    }
  },
  computed: {
    roles() {
      return Role
    },
    prompts() {
      return PromptEnum
    },
    routes() {
      return jsRoutes.controllers
    },
    isCurrentUser() {
      return this.currentUser && this.user && this.currentUser.name === this.user.name
    },
    canEditOrgSettings() {
      return this.orgaPermissions.includes('edit_organization_settings')
    },
    config() {
      return config
    },
    ...mapState('global', ['currentUser', 'permissions', 'headerData']),
    ...mapState('user', ['user', 'orga', 'orgaPermissions']),
  },
  watch: {
    user(val, oldVal) {
      if (!oldVal || val.name !== oldVal.name) {
        this.resetTagline()
      }
    },
  },
  methods: {
    prettifyDate(rawDate) {
      return new Date(rawDate).toLocaleDateString('default', { year: 'numeric', month: 'long', day: 'numeric' })
    },
    avatarUrl,
    resetTagline() {
      this.editTagline = this.user.tagline ? this.user.tagline : ''
    },
    updateTagline() {
      const taglineUrl = jsRoutes.controllers.Users.saveTagline(this.user.name).absoluteURL()
      const data = new FormData()
      data.append('tagline', this.editTagline)
      data.append('csrfToken', window.csrf)

      fetch(taglineUrl, {
        credentials: 'same-origin',
        method: 'post',
        body: data,
      }).then((res) => {
        if (res.ok) {
          this.$store.commit({
            type: 'user/setTagline',
            tagline: this.editTagline,
          })
          if (this.user.name === this.currentUser.name) {
            this.$store.commit({
              type: 'global/setTagline',
              tagline: this.editTagline,
            })
          }
          this.$refs.taglineModal.hide()
        } else {
          genericError(this, 'An error occoured when setting tagline')
        }
      })
    },
  },
}
</script>
