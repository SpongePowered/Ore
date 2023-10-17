import 'regenerator-runtime/runtime.js'

import Vue from 'vue'
import VueRouter from 'vue-router'
import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'

import TopPage from '../pages/TopPage'
import Home from '../pages/Home'
import ProjectDiscussion from '../pages/project/ProjectDiscussion'
import ProjectSettings from '../pages/project/ProjectSettings'
import ProjectDocs from '../pages/project/ProjectDocs'
import ProjectVersions from '../pages/project/ProjectVersions'
import Project from '../pages/project/Project'
import VersionPage from '../pages/project/VersionPage'
import NewVersion from '../pages/project/NewVersion'
import NewProject from '../pages/project/NewProject'
import User from '../pages/user/User'
import _404NotFound from '../pages/404NotFound'

import { store } from '../stores/index'
import UserProjects from '../pages/user/UserProjects'

Vue.use(VueRouter)
Vue.component('FontAwesomeIcon', FontAwesomeIcon)

store.dispatch('global/loadUser')

const router = new VueRouter({
  base: '/',
  mode: 'history',
  routes: [
    {
      path: '/',
      name: 'home',
      component: Home,
    },
    {
      path: '/404/:routeUri+',
      name: 'not_found_404',
      component: _404NotFound,
      props: true,
    },
    {
      path: '/:user',
      component: User,
      props: true,
      children: [
        {
          path: '',
          name: 'user_projects',
          component: UserProjects,
        },
      ],
    },
    {
      path: '/projects/new',
      name: 'new_project',
      component: NewProject,
    },
    {
      path: '/:owner/:slug/',
      component: Project,
      props: true,
      children: [
        {
          path: '',
          name: 'project_home',
          component: ProjectDocs,
          props: {
            page: ['Home'],
          },
        },
        {
          path: 'pages/:page+',
          name: 'pages',
          component: ProjectDocs,
          props: true,
        },
        {
          path: 'versions',
          name: 'versions',
          component: ProjectVersions,
        },
        {
          path: 'versions/new',
          name: 'new_version',
          component: NewVersion,
        },
        {
          path: 'versions/:version',
          name: 'version',
          component: VersionPage,
          props: true,
        },
        {
          path: 'discuss',
          name: 'discussion',
          component: ProjectDiscussion,
        },
        {
          path: 'settings',
          name: 'settings',
          component: ProjectSettings,
        },
      ],
    },
  ],
})

router.beforeEach((to, from, next) => {
  store.commit('dismissAllAlerts')
  next()
})

// eslint-disable-next-line no-new
new Vue({
  el: '#home',
  render: (createElement) => createElement(TopPage),
  router,
  store,
})
