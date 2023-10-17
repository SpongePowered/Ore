<template>
  <div class="row">
    <div class="col-md-8">
      <ProjectList
        v-if="user"
        :owner="user.name"
        :offset="(page - 1) * limit"
        :limit="limit"
        @prev-page="page--"
        @next-page="page++"
        @jump-to-page="page = $event"
      />
    </div>
    <div class="col-md-4">
      <div v-if="orga && canEditOrgMembers" class="panel-user-info panel panel-default">
        <div class="panel-heading">
          <h3 class="panel-title">Project Manager</h3>
        </div>
        <table class="table panel-body">
          <tbody>
            <tr v-for="membership in membershipProjects" :key="membership.project.plugin_id">
              <td>
                <router-link
                  :to="{
                    name: 'project_home',
                    params: membership.project.namespace,
                  }"
                >
                  {{ membership.project.namespace.owner }}/{{ membership.project.namespace.slug }}
                </router-link>
                <span class="minor">{{ roles.byId(membership.role).title }}</span>
              </td>
              <td>
                <template v-if="user && membership.role !== roles.ProjectOwner.name">
                  <button
                    v-if="membership.is_accepted"
                    class="btn btn-sm btn-danger pull-right btn-invite"
                    data-invite-id="@role.id"
                    :data-invite-behalf="user.name"
                    data-invite-accepted="decline"
                  >
                    Leave
                  </button>
                  <button
                    v-else
                    class="btn btn-sm btn-info pull-right btn-invite"
                    data-invite-id="@role.id"
                    :data-invite-behalf="user.name"
                    data-invite-accepted="accept"
                  >
                    Join
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <template v-if="!orga">
        <div class="panel panel-default">
          <div class="panel-heading">
            <h3 class="panel-title">Organizations</h3>
          </div>
          <table v-if="user" class="table panel-body">
            <tbody>
              <tr v-if="!membershipOrgs.length">
                <td>
                  <i class="minor"><i class="fas fa-star" /> {{ user.name }} is not part of any organizations. :(</i>
                </td>
              </tr>
              <tr v-for="membership in membershipOrgs" v-else :key="membership.organization.name">
                <td>
                  <icon
                    :src="avatarUrl(membership.organization.name)"
                    :name="membership.organization.name"
                    extra-classes="user-avatar-xxs"
                  />
                  <router-link :to="{ name: 'user_projects', params: { user: membership.organization.name } }">
                    {{ membership.organization.name }}
                  </router-link>
                  <div class="pull-right">
                    {{ roles.byId(membership.role).title }}
                  </div>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="panel-footer">
            <div class="clearfix" />
          </div>
        </div>

        <projects-panel
          v-if="user"
          title="Stars"
          action="starred"
          :none-found="user.name + ' has not starred any projects. :('"
        />
        <projects-panel
          v-if="user"
          title="Watching"
          action="watching"
          :none-found="user.name + ' is not watching any projects. :('"
        />
      </template>
      <div v-else>
        <member-list
          v-if="user"
          role-category="organization"
          :members="orgaMembers"
          :permissions="orgaPermissions"
          editable
          exclude-organizations
          new-role="Organization_Support"
          :endpoint="'users/' + user.name + '/members'"
          commit-location="user/updateMembers"
        />
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import Icon from '../../components/Icon'
import { avatarUrl } from '../../utils'
import MemberList from '../../components/MemberList'
import { Role } from '../../enums'
import ProjectsPanel from '../../components/ProjectsPanel'
import ProjectList from './../../components/ProjectList'

export default {
  components: {
    ProjectsPanel,
    MemberList,
    ProjectList,
    Icon,
  },
  data() {
    return {
      page: 1,
      limit: 5,
    }
  },
  computed: {
    roles() {
      return Role
    },
    canEditOrgMembers() {
      return this.orgaPermissions.includes('manage_organization_members')
    },
    ...mapState('user', ['user', 'orga', 'orgaPermissions', 'membershipOrgs', 'membershipProjects', 'orgaMembers']),
  },
  methods: {
    avatarUrl,
  },
}
</script>
