<template>
  <div>
    <div v-if="updateError" class="alert alert-danger member-error">
      <span>{{ updateError }}</span>
    </div>

    <div class="panel panel-default">
      <div class="panel-heading">
        <h3 class="pull-left panel-title">Members</h3>

        <div v-if="permissions.includes('manage_subject_members')" class="pull-right">
          <router-link v-if="!editable && settingsRoute" v-slot="{ href, navigate }" :to="settingsRoute">
            <a v-if="!editable" :href="href" class="btn yellow btn-xs" @click="navigate">
              <FontAwesomeIcon :icon="['fas', 'pencil-alt']" />
            </a>
          </router-link>

          <button
            v-show="editable && madeChanges"
            class="btn btn-default btn-panel btn-xs"
            data-toggle="tooltip"
            data-placement="top"
            data-title="Save Users"
            aria-label="Save Users"
            @click="saveMembers"
          >
            <FontAwesomeIcon :icon="['fas', spinIcon ? 'spinner' : 'paper-plane']" :spin="spinIcon" />
          </button>
          <button
            v-show="editable && madeChanges"
            class="btn btn-default btn-panel btn-xs"
            data-toggle="tooltip"
            data-placement="top"
            data-title="Discard changes"
            aria-label="Discard changes"
            @click="resetEdit"
          >
            <FontAwesomeIcon :icon="['fas', 'times']" />
          </button>
        </div>
      </div>

      <ul class="list-members list-group">
        <!-- Member list -->
        <template v-for="member in updatedMembers">
          <li :key="member.user" class="list-group-item">
            <icon :name="member.user" :src="avatarUrl(member.user)" extra-classes="user-avatar-xs" />
            <router-link class="username" :to="{ name: 'user_projects', params: { user: member.user } }">
              {{ member.user }}
            </router-link>

            <template v-if="editable && permissions.includes('manage_subject_members') && member.role.isAssignable">
              <a href="#" @click="removeUser(member.user)">
                <FontAwesomeIcon style="padding-left: 5px" :icon="['fas', 'trash']" />
              </a>

              <role-select
                :value="member.role.name"
                :role-category="roleCategory"
                @input="setMemberRole(member.user, $event)"
              />
            </template>
            <span v-else class="minor pull-right">
              <span v-if="member.role.is_accepted">{{ member.role.title }}</span>
              <span v-else class="minor">(Invited as {{ member.role.title }})</span>
            </span>
          </li>
        </template>

        <!-- User search -->
        <li v-if="permissions.includes('manage_subject_members') && editable" class="list-group-item">
          <user-search
            :exclude="Object.values(updatedMembers).map((m) => m.user)"
            :exclude-organizations="excludeOrganizations"
            style="width: 100%"
            @add-user="addNewMember"
          />
        </li>
      </ul>
    </div>
  </div>
</template>

<script>
import { avatarUrl } from '../utils'
import { Role } from '../enums'
import { API } from '../api'
import Icon from './Icon'
import UserSearch from './UserSearch'
import RoleSelect from './RoleSelect'

export default {
  components: {
    Icon,
    UserSearch,
    RoleSelect,
  },
  props: {
    roleCategory: {
      type: String,
      required: true,
    },
    members: {
      type: Array,
      required: true,
    },
    permissions: {
      type: Array,
      required: true,
    },
    editable: {
      type: Boolean,
      default: false,
    },
    newRole: {
      type: String,
      default: null,
    },
    settingsRoute: {
      type: Object,
      default: null,
    },
    endpoint: {
      type: String,
      default: null,
    },
    commitLocation: {
      type: String,
      default: null,
    },
    excludeOrganizations: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      newUserRole: this.newRole,
      updatedMembers: this.memberArrayToObj(this.members),
      madeChanges: false,
      updateError: null,
      spinIcon: false,
    }
  },
  computed: {
    roles() {
      return Role
    },
  },
  watch: {
    members(val) {
      if (!this.madeChanges) {
        this.updatedMembers = this.memberArrayToObj(val)
      }
    },
    madeChanges: {
      handler() {
        $('[data-toggle="tooltip"]').tooltip()
      },
      immediate: true,
    },
  },
  methods: {
    avatarUrl,
    memberArrayToObj(arr) {
      return Object.fromEntries(
        arr.map((member) => [
          member.user,
          {
            user: member.user,
            role: { ...Role.byId(member.role.name), is_accepted: member.role.is_accepted },
          },
        ])
      )
    },
    memberObjToArray(obj) {
      return Object.values(obj).map(({ user, role }) => ({ user, role: role.name }))
    },
    resetNewUserRole() {
      this.newUserRole = this.newRole
    },
    addNewMember(user) {
      this.$set(this.updatedMembers, user.name, { user: user.name, role: Role.byId(this.newUserRole) })
      this.resetNewUserRole()
      this.madeChanges = true
    },
    removeUser(user) {
      delete this.updatedMembers[user]
      this.madeChanges = true
    },
    setMemberRole(user, role) {
      this.$set(this.updatedMembers[user], 'role', Role.byId(role))
      this.madeChanges = true
    },
    resetEdit() {
      this.updatedMembers = this.memberArrayToObj(this.members)
      this.madeChanges = false
    },
    saveMembers() {
      this.spinIcon = true
      const memberArr = this.memberObjToArray(this.updatedMembers)
      API.request(this.endpoint, 'POST', memberArr).then((res) => {
        this.spinIcon = false

        if (this.commitLocation) {
          this.$store.commit({
            type: this.commitLocation,
            members: res,
          })
          this.resetEdit()
        }
      })
    },
  },
}
</script>
