import { API } from '../api'

const state = {
  currentUser: null,
  permissions: [],
  memberships: [],
  headerData: null,
}
const mutations = {
  setUser(state, payload) {
    state.currentUser = payload.user
  },
  updatePermissions(state, payload) {
    state.permissions = payload.permissions
  },
  setHeaderData(state, payload) {
    state.headerData = payload.headerData
  },
  setMemberships(state, payload) {
    state.memberships = payload.memberships
  },
  setTagline(state, payload) {
    state.currentUser.tagline = payload.tagline
  },
}
const actions = {
  loadUser(context) {
    if (API.hasUser()) {
      API.request('users/@me').then((res) => {
        context.commit({
          type: 'setUser',
          user: res,
        })

        API.request(`users/${res.name}/memberships`).then((res) => {
          context.commit({
            type: 'setMemberships',
            memberships: res,
          })
        })
      })

      API.request('permissions').then((response) => {
        context.commit({
          type: 'updatePermissions',
          permissions: response.permissions,
        })
      })

      API.request('_headerdata').then((res) => {
        context.commit({
          type: 'setHeaderData',
          headerData: res,
        })
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
