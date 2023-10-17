<template>
  <div class="panel-user-info panel panel-default" data-action="starred">
    <div class="panel-heading">
      <h3 class="panel-title"><i class="fas fa-star" />{{ title }}</h3>
    </div>
    <table class="table panel-body">
      <tbody>
        <tr v-if="!pagination || pagination.count === 0">
          <td>
            <i class="minor">{{ noneFound }}</i>
          </td>
        </tr>
        <tr v-for="project in projects" v-else :key="project.plugin_id">
          <td>
            <router-link :to="{ name: 'project_home', params: project.namespace }">
              {{ project.namespace.owner }}/<strong>{{ project.namespace.slug }}</strong>
            </router-link>
            <div class="pull-right">
              <span v-if="project.promoted_versions.length" class="minor">{{
                project.promoted_versions[0].version
              }}</span>
              <FontAwesomeIcon
                :icon="['fas', categories.fromId(project.category).icon]"
                fixed-width
                :title="categories.fromId(project.category).name"
              />
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="panel-footer">
      <div class="pull-right">
        <a v-if="showPrev" class="prev" href="#" @click="loadActions(-1)">&laquo;</a>
        <a v-if="showNext" class="next" href="#" @click="loadActions(1)">&raquo;</a>
      </div>
      <div class="clearfix" />
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { API } from '../api'
import { Category } from '../enums'

const CONTENT_PER_PAGE = 5

export default {
  props: {
    title: {
      type: String,
      required: true,
    },
    action: {
      type: String,
      required: true,
    },
    noneFound: {
      type: String,
      required: true,
    },
  },
  data() {
    return {
      page: 1,
      pagination: null,
      projects: [],
    }
  },
  computed: {
    showNext() {
      return this.pagination && this.pagination.count > this.page * CONTENT_PER_PAGE
    },
    showPrev() {
      return this.page > 1
    },
    categories() {
      return Category
    },
    ...mapState('user', ['user']),
  },
  watch: {
    user: {
      handler(val, oldVal) {
        if (!oldVal || val.name !== oldVal.name) {
          this.loadActions(0, 1)
        }
      },
      immediate: true,
    },
  },
  methods: {
    loadActions(increment, set) {
      if (set) {
        this.page = set
      }

      this.page += increment
      const offset = (this.page - 1) * CONTENT_PER_PAGE

      API.request(
        'users/' + this.user.name + '/' + this.action + '?offset=' + offset + '&limit=' + CONTENT_PER_PAGE
      ).then((result) => {
        this.pagination = result.pagination
        this.projects = result.result
      })
    },
  },
}
</script>
