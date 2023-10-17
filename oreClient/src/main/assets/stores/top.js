import Vue from 'vue'

const state = {
  alerts: {
    error: [],
    success: [],
    info: [],
    warning: [],
  },
}

const mutations = {
  addAlert(state, payload) {
    state.alerts[payload.level].push({
      message: payload.message,
      tag: payload.tag,
    })
  },
  addAlerts(state, payload) {
    state.alerts[payload.level] = state.alerts[payload.level].concat(
      payload.messages.map((m) => {
        return { message: m }
      })
    )
  },
  replaceAlert(state, payload) {
    mutations.dismissAlertsByTag(state, payload)
    mutations.addAlert(state, payload)
  },
  dismissAlert(state, payload) {
    state.alerts[payload.level].splice(payload.index, 1)
  },
  dismissAlertsByType(state, payload) {
    Vue.set(state.alerts, payload.level, [])
  },
  dismissAlertsByTag(state, payload) {
    Vue.set(
      state.alerts,
      payload.level,
      state.alerts[payload.level].filter((m) => m.tag !== payload.tag)
    )
  },
  dismissAllAlerts(state, payload) {
    Object.keys(state.alerts).forEach((v) => {
      Vue.set(state.alerts, v, [])
    })
  },
}

export default {
  state,
  mutations,
}
