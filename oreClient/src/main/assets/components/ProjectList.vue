<template>
  <div>
    <div v-show="loading">
      <FontAwesomeIcon spin :icon="['fas', 'spinner']" />
      <span>Loading projects for you...</span>
    </div>
    <div v-show="!loading">
      <div v-if="projects.length > 0">
        <ul class="list-group project-list">
          <li
            v-for="project in projects"
            :key="project.plugin_id"
            class="list-group-item project"
            :class="visibilityFromName(project.visibility).class"
          >
            <div class="container-fluid">
              <div class="row">
                <div class="col-xs-12 col-sm-1">
                  <Icon :name="project.namespace.owner" :src="project.icon_url" extra-classes="user-avatar-sm" />
                </div>
                <div class="col-xs-12 col-sm-11">
                  <div class="row">
                    <div class="col-sm-6">
                      <router-link v-slot="{ href, navigate }" :to="routerLinkProject(project)">
                        <a :href="href" class="title" @click="navigate">
                          {{ project.name }}
                        </a>
                      </router-link>
                    </div>
                    <div class="col-sm-6 hidden-xs">
                      <div class="info minor">
                        <span class="stat" title="Views">
                          <FontAwesomeIcon :icon="['fas', 'eye']" />
                          {{ formatStats(project.stats.views) }}
                        </span>
                        <span class="stat" title="Download">
                          <FontAwesomeIcon :icon="['fas', 'download']" />
                          {{ formatStats(project.stats.downloads) }}
                        </span>
                        <span class="stat" title="Stars">
                          <FontAwesomeIcon :icon="['fas', 'star']" /> {{ formatStats(project.stats.stars) }}
                        </span>

                        <span :title="categoryFromId(project.category).name" class="stat">
                          <FontAwesomeIcon :icon="['fas', categoryFromId(project.category).icon]" />
                        </span>
                      </div>
                    </div>
                  </div>
                  <div class="row">
                    <div class="col-sm-7 summary-column">
                      <div class="summary">
                        {{ project.summary }}
                      </div>
                    </div>
                    <div v-if="project.promoted_versions" class="col-xs-12 col-sm-5 tags-line">
                      <Tag
                        v-for="platform in platformsFromPromoted(project.promoted_versions)"
                        :key="project.name + '-' + platform.name"
                        :name="platform.name"
                        :data="platform.versions.join(' | ')"
                        :color="platform.color"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </li>
        </ul>
        <Pagination
          :current="current"
          :total="total"
          @jump-to="$emit('jump-to-page', $event)"
          @next="$emit('next-page')"
          @prev="$emit('prev-page')"
        />
      </div>
      <div v-else class="list-group-item empty-project-list">
        <FontAwesomeIcon :icon="['far', 'sad-tear']" size="2x" />
        <span>Oops! No projects found...</span>
      </div>
    </div>
  </div>
</template>

<script>
import debounce from 'lodash/debounce'
import NProgress from 'nprogress'
import npcss from 'nprogress/nprogress.css'
import { Category, Platform, Visibility } from '../enums'
import { API } from '../api'
import { clearFromEmpty, numberWithCommas } from '../utils'
import Tag from './Tag'
import Pagination from './Pagination'
import Icon from './Icon'

void npcss // eslint-disable-line no-void

export default {
  components: {
    Tag,
    Pagination,
    Icon,
  },
  props: {
    q: {
      type: String,
      default: null,
    },
    categories: {
      type: Array,
      default: null,
    },
    platforms: {
      type: Array,
      default: null,
    },
    owner: {
      type: String,
      default: null,
    },
    sort: {
      type: String,
      default: null,
    },
    relevance: {
      type: Boolean,
      default: true,
    },
    limit: {
      type: Number,
      default: 25,
    },
    offset: {
      type: Number,
      default: 0,
    },
  },
  data() {
    return {
      projects: [],
      totalProjects: 0,
      loading: true,
    }
  },
  computed: {
    current() {
      return Math.ceil(this.offset / this.limit) + 1
    },
    total() {
      return Math.ceil(this.totalProjects / this.limit)
    },
    routes() {
      return jsRoutes.controllers.project
    },
  },
  created() {
    this.update()
    this.debouncedUpdateProps = debounce(this.update, 500)
    this.$watch(
      (vm) => [vm.q, vm.categories, vm.platforms, vm.owner, vm.sort, vm.relevance, vm.limit, vm.offset].join(),
      () => {
        this.debouncedUpdateProps()
      }
    )
  },
  methods: {
    update() {
      NProgress.start()
      API.request('projects', 'GET', clearFromEmpty(this.$props)).then((response) => {
        NProgress.done()
        this.projects = response.result
        this.totalProjects = response.pagination.count
        this.loading = false
        this.$emit('update:projectCount', this.totalProjects)
      })
    },
    categoryFromId(id) {
      return Category.fromId(id)
    },
    visibilityFromName(name) {
      return Visibility.fromName(name)
    },
    platformsFromPromoted(promotedVersions) {
      let platformArray = []
      promotedVersions
        .map((version) => version.platforms)
        .forEach((platforms) => {
          platformArray = platforms.concat(platformArray)
        })

      const reducedPlatforms = []

      Platform.values.forEach((platform) => {
        const versions = []
        platformArray
          .filter((plat) => plat.platform === platform.id)
          .reverse()
          .forEach((plat) => {
            versions.push(plat.display_platform_version || plat.platform_version)
          })

        if (versions.length > 0) {
          reducedPlatforms.push({
            name: platform.shortName,
            versions,
            color: platform.color,
          })
        }
      })

      return reducedPlatforms
    },
    formatStats(number) {
      return numberWithCommas(number)
    },
    routerLinkProject(project) {
      return {
        name: 'project_home',
        params: {
          fetchedProject: project,
          owner: project.namespace.owner,
          slug: project.namespace.slug,
        },
      }
    },
  },
}
</script>

<style lang="scss">
@import './../scss/variables';

.empty-project-list {
  display: flex;
  align-items: center;

  span {
    margin-left: 0.5rem;
  }
}
.project-list {
  margin-bottom: 0;

  .row {
    display: flex;
    flex-wrap: nowrap;
  }

  @media (max-width: 767px) {
    .row {
      display: flex;
      flex-wrap: wrap;
    }
  }

  .project {
    padding: 10px 0;
    margin-bottom: 0.25rem;

    &:last-child {
      margin-bottom: 0;
    }
  }

  .title {
    font-size: 2rem;
    color: $sponge_grey;
    font-weight: bold;
  }

  .summary-column {
    overflow: hidden;

    .summary {
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
  }

  .tags-line {
    display: flex;
    justify-content: flex-end;

    @media (max-width: 480px) {
      justify-content: flex-start;
      margin-top: 0.5rem;
    }

    .tags {
      margin-right: 0.5rem;
    }

    :last-child {
      margin-right: 0;
    }

    .tag {
      margin: 0;
    }
  }

  .info {
    display: flex;
    justify-content: flex-end;

    span {
      margin-right: 1.5rem;

      &:last-child {
        margin-right: 0;
      }

      &.recommended-version a {
        font-weight: bold;
        color: #636363;
      }
    }
  }
}
</style>
