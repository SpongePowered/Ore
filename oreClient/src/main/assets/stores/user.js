import { API } from '../api'

const state = {
  user: null,
  orga: null,
  membershipOrgs: [],
  membershipProjects: [],
  orgaPermissions: [],
  orgaMembers: [],
  notFound: false,
}

const mutations = {
  setUser(state, payload) {
    state.user = payload.user
    state.notFound = false
  },
  setOrga(state, payload) {
    state.orga = payload.orga
  },
  setOrgaPermissions(state, payload) {
    state.orgaPermissions = payload.orgaPermissions
  },
  setMemberships(state, payload) {
    state.membershipOrgs = payload.memberships.filter((m) => m.scope === 'organization')
    state.membershipProjects = payload.memberships.filter((m) => m.scope === 'project')
  },
  clearUser(state) {
    state.user = null
    state.orga = null
    state.membershipOrgs = []
    state.membershipProjects = []
    state.orgaPermissions = []
    state.orgaMembers = []
    state.notFound = false
  },
  updateMembers(state, payload) {
    state.orgaMembers = payload.members
  },
  setTagline(state, payload) {
    state.user.tagline = payload.tagline
  },
  userNotFound(state) {
    state.notFound = true
  },
}

const actions = {
  setActiveUser(context, user) {
    if (!context.state.user || context.state.user.name !== user) {
      context.commit('clearUser')

      API.request('users/' + user)
        .then((res) => {
          context.commit({
            type: 'setUser',
            user: res,
          })
        })
        .catch((error) => {
          if (error === 404) {
            context.commit('userNotFound')
          }
        })

      API.request('users/' + user + '/memberships').then((res) => {
        context.commit({
          type: 'setMemberships',
          memberships: res,
        })
      })

      API.request('organizations/' + user)
        .then((res) => {
          context.commit({
            type: 'setOrga',
            orga: res,
          })

          API.request('organizations/' + user + '/members').then((response) => {
            context.commit({
              type: 'updateMembers',
              members: response,
            })
          })

          API.request('permissions', 'GET', { organization: user }).then((response) => {
            context.commit({
              type: 'setOrgaPermissions',
              orgaPermissions: response.permissions,
            })
          })
        })
        .catch((res) => {
          if (res !== 404) {
            context.commit(
              'addAlert',
              {
                level: 'error',
                message: 'Failed to get organization info for user',
              },
              { root: true }
            )
          }
        })
    }
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions,
}
