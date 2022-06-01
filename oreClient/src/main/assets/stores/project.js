import Vue from 'vue'
import isEqual from 'lodash/isEqual'
import { API } from '../api'

const state = {
  project: null,
  permissions: [],
  members: [],
  notFound: false,
}
const mutations = {
  updateProject(state, payload) {
    state.notFound = false
    state.project = payload.project
  },
  updatePermissions(state, payload) {
    state.permissions = payload.permissions
  },
  updateMembers(state, payload) {
    state.members = payload.members
  },
  setVisibility(state, payload) {
    Vue.set(state.project, 'visibility', payload.visibility)
  },
  clearProject(state) {
    state.project = null
    state.permissions = []
    state.members = []
  },
  toggleStarred(state) {
    if (state.project.user_actions.starred) {
      Vue.set(state.project.stats, 'stars', state.project.stats.stars - 1)
    } else {
      Vue.set(state.project.stats, 'stars', state.project.stats.stars + 1)
    }

    Vue.set(state.project.user_actions, 'starred', !state.project.user_actions.starred)
  },
  setWatching(state, payload) {
    const watching = payload.watching
    if (state.project.user_actions.watching !== watching) {
      if (watching) {
        Vue.set(state.project.stats, 'watchers', state.project.stats.watchers + 1)
      } else {
        Vue.set(state.project.stats, 'watchers', state.project.stats.watchers - 1)
      }
    }

    Vue.set(state.project.user_actions, 'watching', watching)
  },
  projectNotFound(state) {
    state.notFound = true
  },
}
const actions = {
  setActiveProjectFromFetched(context, project) {
    const projectsDiffer = !context.state.project || context.state.project.plugin_id !== project.plugin_id
    context.commit({
      type: 'updateProject',
      project,
    })

    if (projectsDiffer || !context.state.permissions.length) {
      API.request('permissions', 'GET', {
        projectOwner: project.namespace.owner,
        projectSlug: project.namespace.slug,
      }).then((response) => {
        context.commit({
          type: 'updatePermissions',
          permissions: response.permissions,
        })
      })
    }

    if (projectsDiffer || !context.state.members.length) {
      API.projectRequest(project.namespace, 'members').then((response) => {
        context.commit({
          type: 'updateMembers',
          members: response,
        })
      })
    }
  },
  async setActiveProject(context, project) {
    if (!context.state.project || !isEqual(context.state.project.namespace, project)) {
      let res
      try {
        res = await API.projectRequest(project)
      } catch (e) {
        if (e === 404) {
          context.dispatch('projectNotFound')
        } else {
          throw e
        }
      }

      if (res) {
        context.dispatch('setActiveProjectFromFetched', res)
      }
    }
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions,
}
