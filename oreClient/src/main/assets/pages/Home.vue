<template>
  <div>
    <div class="index-header">
      <div class="row centered-content-row">
        <div class="col-md-9 ore-banner">
          <div class="row aligned-row">
            <div class="col-xs-2 ore-logo">
              <img src="../images/ore-colored.svg" alt="Ore logo" />
            </div>
            <div class="col-xs-10 text">
              <div class="headline">Ore</div>
              <div>A Minecraft package repository</div>
            </div>
          </div>
        </div>
        <div class="col-md-3 sponsor">
          <div class="panel sponsor-panel">
            <span>Sponsored by</span>
            <div class="panel-body" :set="(sponsor = randomSponsor)">
              <a class="logo-container" :href="sponsor.link">
                <img class="logo" :src="sponsor.image" alt="Sponsor" />
              </a>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="row">
      <div class="col-md-9">
        <div class="project-search" :class="{ 'input-group': q.length > 0 }">
          <input v-model="q" type="text" class="form-control" :placeholder="queryPlaceholder" @keydown="resetPage" />
          <span v-if="q.length > 0" class="input-group-btn">
            <button class="btn btn-default" type="button" @click="q = ''">
              <FontAwesomeIcon :icon="['fas', 'times']" />
            </button>
          </span>
        </div>
        <div v-if="!isDefault" class="clearSelection">
          <a @click="reset">
            <FontAwesomeIcon :icon="['fas', 'window-close']" />
            Clear current search query, categories, platform, and sort</a
          >
        </div>
        <project-list
          ref="list"
          v-bind="listBinding"
          :project-count.sync="projectCount"
          @prevPage="page--"
          @nextPage="page++"
          @jump-to-page="page = $event"
        />
      </div>
      <div class="col-md-3">
        <select v-model="sort" class="form-control select-sort" @change="resetPage">
          <option v-for="option in availableOptions.sort" :key="option.id" :value="option.id">
            {{ option.name }}
          </option>
        </select>

        <div>
          <input id="relevanceBox" v-model="relevance" type="checkbox" />
          <label for="relevanceBox">Sort with relevance</label>
          <div class="panel panel-default">
            <div class="panel-heading">
              <h3 class="panel-title">Categories</h3>
              <a v-if="categories.length > 0" class="category-reset" @click="categories = []">
                <FontAwesomeIcon class="white" :icon="['fas', 'times']" />
              </a>
            </div>

            <div class="list-group category-list">
              <a
                v-for="category in availableOptions.category"
                :key="category.id"
                class="list-group-item"
                :class="{ active: categories.includes(category.id) }"
                @click="changeCategory(category)"
              >
                <FontAwesomeIcon fixed-width :icon="['fas', category.icon]" />
                <strong>{{ category.name }}</strong>
              </a>
            </div>
          </div>
          <div class="panel panel-default">
            <div class="panel-heading">
              <h3 class="panel-title">Platforms</h3>
            </div>

            <div class="list-group platform-list">
              <a class="list-group-item" :class="{ active: platforms.length === 0 }" @click="platforms = []">
                <span class="parent">Any</span>
              </a>
              <a
                v-for="platform in availableOptions.platform"
                :key="platform.id"
                class="list-group-item"
                :class="{ active: platforms.includes(platform.id) }"
                @click="platforms = [platform.id]"
              >
                <span :class="{ parent: platform.parent }">{{ platform.name }}</span>
              </a>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import queryString from 'query-string'
import ProjectList from './../components/ProjectList'
import { clearFromDefaults } from './../utils'
import { Category, Platform, SortOptions } from './../enums'
import sponsors from './sponsors.js'

function defaultData() {
  return {
    q: '',
    sort: 'updated',
    relevance: true,
    categories: [],
    platforms: [],
    page: 1,
    offset: 0,
    limit: 25,
    projectCount: null,
    availableOptions: {
      category: Category.values,
      platform: Platform.values,
      sort: SortOptions,
    },
  }
}

export default {
  components: {
    ProjectList,
  },
  data: defaultData,
  computed: {
    isDefault() {
      return Object.keys(clearFromDefaults(this.baseBinding, defaultData())).length === 0
    },
    baseBinding() {
      return {
        q: this.q,
        sort: this.sort,
        relevance: this.relevance,
        categories: this.categories,
        platforms: this.platforms,
      }
    },
    listBinding() {
      return clearFromDefaults(
        Object.assign({}, this.baseBinding, { offset: (this.page - 1) * this.limit, limit: this.limit }),
        defaultData()
      )
    },
    urlBinding() {
      return clearFromDefaults(Object.assign({}, this.baseBinding, { page: this.page }), defaultData())
    },
    queryPlaceholder() {
      return (
        `Search in ${this.projectCount === null ? 'all' : this.projectCount} projects` +
        `${!this.isDefault ? ' matching your filters' : ''}` +
        ', proudly made by the community...'
      )
    },
    randomSponsor() {
      const index = Math.floor(Math.random() * sponsors.length)
      return sponsors[index]
    },
  },
  watch: {
    page() {
      window.scrollTo(0, 0)
    },
  },
  created() {
    Object.entries(queryString.parse(location.search, { arrayFormat: 'bracket', parseBooleans: true }))
      .filter(([key, value]) => Object.prototype.hasOwnProperty.call(defaultData(), key))
      .forEach(([key, value]) => {
        this.$data[key] = value
      })

    this.$watch(
      (vm) => [vm.q, vm.sort, vm.relevance, vm.categories, vm.platforms, vm.page].join(),
      () => {
        const query = queryString.stringify(this.urlBinding, { arrayFormat: 'bracket' })
        window.history.pushState(null, null, query !== '' ? '?' + query : '/')
      }
    )
    this.$watch(
      (vm) => [vm.q, vm.sort, vm.relevance, vm.categories, vm.platforms].join(),
      () => {
        this.resetPage()
      }
    )
  },
  methods: {
    reset() {
      Object.entries(defaultData()).forEach(([key, value]) => {
        this.$data[key] = value
      })
    },
    resetPage() {
      this.page = 1
    },
    changeCategory(category) {
      if (this.categories.includes(category.id)) {
        this.categories.splice(this.categories.indexOf(category.id), 1)
      } else if (this.categories.length + 1 === Category.values.length) {
        this.categories = []
      } else {
        this.categories.push(category.id)
      }
    },
  },
}
</script>

<style lang="scss">
@import './../scss/variables';

.select-sort {
  margin-bottom: 10px;
}
.category-reset {
  display: flex;
  cursor: pointer;
}
.category-list {
  a.list-group-item {
    svg {
      margin-right: 0.5rem;
    }

    &:hover {
      cursor: pointer;
      background-color: $mainBackground;
    }

    &.active {
      background: #ffffff;
      border-bottom: 1px solid #dddddd;
      border-top: 1px solid #dddddd;
      box-shadow: inset -10px 0px 0px 0px $sponge_yellow;
    }
  }
}
.platform-list {
  .list-group-item {
    cursor: pointer;
  }
  .parent {
    font-weight: bold;
  }
}
.clearSelection {
  margin-bottom: 1rem;
  a {
    cursor: pointer;
    color: #586069;
  }
}
</style>
