import Vuex from 'vuex'
import Vue from 'vue'
import topStore from './top'
import projectModule from './project'
import globalModule from './global'
import userModule from './user'

Vue.use(Vuex)

export const store = new Vuex.Store({
  ...topStore,
  modules: {
    project: projectModule,
    global: globalModule,
    user: userModule,
  },
})
