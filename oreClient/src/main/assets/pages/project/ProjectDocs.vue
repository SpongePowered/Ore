<template>
  <div class="row">
    <div class="col-md-9">
      <div class="row">
        <div class="col-md-12">
          <editor
            :enabled="permissions.includes('edit_page')"
            :raw="description"
            subject="Page"
            :markdown-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/`"
            :markdown-relative-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/${pageParent}`"
            @saved="savePage"
          />
        </div>
      </div>
    </div>

    <div class="col-md-3">
      <div v-if="project" class="stats minor">
        <p>Category: {{ parseCategory(project.category) }}</p>
        <p>Published on {{ parseDate(project.created_at) }}</p>
        <p>{{ formatStats(project.stats.views) }} views</p>
        <p>{{ formatStats(project.stats.downloads) }} total downloads</p>
        <p v-if="project.settings.license.name !== null">
          <span>Licensed under </span>
          <a target="_blank" rel="noopener" :href="project.settings.license.url">{{ project.settings.license.name }}</a>
        </p>
      </div>

      <div class="panel panel-default">
        <div class="panel-heading">
          <h3 class="panel-title">Promoted Versions</h3>
        </div>

        <ul v-if="project" class="list-group promoted-list">
          <li
            v-for="version in project.promoted_versions"
            :key="version.version"
            class="list-group-item row row-no-gutters"
            style="line-height: 2.4em"
          >
            <div class="col-lg-8 col-12">
              <router-link
                v-slot="{ href, navigate }"
                :to="{ name: 'version', params: { project, permissions, version: version.version } }"
              >
                <a :href="href" @click="navigate">{{ version.version }}</a>
              </router-link>
            </div>
            <div class="col-lg-4 col-12">
              <a
                class="pull-right btn btn-primary"
                :href="
                  routes.Versions.download(
                    project.namespace.owner,
                    project.namespace.slug,
                    version.version,
                    null
                  ).absoluteURL()
                "
              >
                <FontAwesomeIcon :icon="['fas', 'download']" /> Download
              </a>
            </div>
          </li>
        </ul>
      </div>

      <div class="panel panel-default">
        <div class="panel-heading">
          <h3 class="panel-title">Pages</h3>
          <template v-if="permissions.includes('edit_page')">
            <button
              class="new-page btn yellow btn-xs pull-right"
              data-toggle="modal"
              data-target="#modal-edit-page"
              title="New"
            >
              <FontAwesomeIcon :icon="['fas', 'plus']" />
            </button>

            <modal
              ref="editPageModal"
              name="edit-page"
              :title="newPage ? 'Create a new page' : 'Edit page'"
              :on-close="resetPutPage"
            >
              <h4 v-if="pagePutError" id="page-label-error" class="modal-title" style="display: none; color: red">
                Error updating page {{ pagePutError }}
              </h4>

              <div class="input-group">
                <div class="setting">
                  <div class="setting-description">
                    <h4>Page name</h4>
                    <p>Enter a title for your page.</p>
                  </div>
                  <div class="setting-content">
                    <input
                      id="page-name"
                      v-model="requestPage.name"
                      class="form-control"
                      type="text"
                      name="page-name"
                    />
                  </div>
                  <div class="clearfix" />
                </div>
                <div class="setting">
                  <div class="setting-description">
                    <h4>Parent page</h4>
                    <p>Select a parent page (optional)</p>
                  </div>
                  <div class="setting-content">
                    <select v-model="requestPage.parent" class="form-control select-parent">
                      <option selected value="null">&lt;none&gt;</option>
                      <option
                        v-for="(forPage, index) in pages"
                        :key="index"
                        :value="forPage.slug.join('/')"
                        :data-slug="forPage.slug.join('/')"
                      >
                        {{ forPage.name.join('/') }}
                      </option>
                    </select>
                  </div>
                  <div class="clearfix" />
                </div>
                <div class="setting setting-no-border">
                  <div class="setting-description">
                    <h4>Navigational</h4>
                    <p>
                      Makes the page only useful for nagivation.
                      <b>This will delete all content currently on the page.</b>
                    </p>
                  </div>
                  <div class="setting-content">
                    <div class="form-check">
                      <input
                        id="page-navigational"
                        v-model="requestPage.navigational"
                        class="form-check-input position-static"
                        type="checkbox"
                        name="page-navigational"
                        aria-label="Navigational"
                      />
                    </div>
                  </div>
                  <div class="clearfix" />
                </div>
              </div>

              <template #footer>
                <button type="button" class="btn btn-default" data-dismiss="modal" @click="resetPutPage">Close</button>
                <button v-if="!newPage" type="button" class="btn btn-danger" @click="deletePage">Delete</button>
                <button
                  :disabled="!requestPage.name || requestPage.name.includes('/')"
                  type="button"
                  class="btn btn-primary"
                  @click="updateCreatePage"
                >
                  Continue
                </button>
              </template>
            </modal>
          </template>
        </div>

        <page-list :pages="groupedPages" @edit-page="startEditPage" />
      </div>

      <member-list
        :permissions="permissions"
        :members="members"
        role-category="project"
        :settings-route="{ name: 'settings' }"
      />
    </div>
  </div>
</template>

<script>
import isEqual from 'lodash/isEqual'
import NProgress from 'nprogress'
import { mapState } from 'vuex'
import { API } from '../../api'
import Editor from '../../components/Editor'
import MemberList from '../../components/MemberList'
import { Category } from '../../enums'
import PageList from '../../components/PageList'
import { genericError, notFound, numberWithCommas, slugify } from '../../utils'
import Modal from '../../components/Modal'

export default {
  components: {
    Modal,
    PageList,
    Editor,
    MemberList,
  },
  props: {
    page: {
      type: [Array, String],
      required: true,
    },
  },
  data() {
    return {
      description: '',
      pages: [],
      requestPage: {},
      pagePutError: null,
    }
  },
  computed: {
    routes() {
      return jsRoutes.controllers.project
    },
    groupedPages() {
      const acc = {}

      for (const page of this.pages) {
        let obj = acc

        for (const k of page.slug.slice(0, -1)) {
          if (typeof obj[k] === 'undefined') {
            obj[k] = {
              children: {},
              navigational: false,
              slug: [],
              name: '',
            }
          }

          obj = acc[k].children
        }

        const key = page.slug[page.slug.length - 1]
        const oldChildren = obj[key]?.children ?? {}
        const isHome = page.slug.length === 1 && page.slug[0] === 'home'
        obj[key] = {
          slug: page.slug,
          name: page.name,
          navigational: page.navigational,
          children: oldChildren,
          isHome,
        }
      }

      return acc
    },
    newPage() {
      return typeof this.requestPage.existing === 'undefined'
    },
    splitPage() {
      return Array.isArray(this.page) ? this.page : this.page.split('/')
    },
    joinedPage() {
      return Array.isArray(this.page) ? this.page.join('/') : this.page
    },
    pageParent() {
      const splitPageCopy = [...this.splitPage]
      splitPageCopy.pop()

      return splitPageCopy.join('/')
    },
    currentPage() {
      return this.pages.filter((p) =>
        isEqual(
          p.slug.map((s) => s.toLowerCase()),
          this.splitPage.map((s) => s.toLowerCase())
        )
      )[0]
    },
    ...mapState('project', ['project', 'permissions', 'members']),
  },
  watch: {
    $route(oldVal, newVal) {
      if (oldVal.path !== newVal.path || oldVal.hash === newVal.hash) {
        this.updatePage(false)
      }
    },
    project: {
      handler(val, oldVal) {
        // eslint-disable-next-line camelcase
        if (val && val.plugin_id !== oldVal?.plugin_id) {
          this.updatePage(true)
        }
      },
      immediate: true,
    },
  },
  methods: {
    updatePage(fetchPages) {
      NProgress.start()
      API.projectRequest(this.project.namespace, '_pages/' + this.joinedPage)
        .then((response) => {
          if (response.content === null) {
            this.description = ''
          } else {
            this.description = response.content
          }
          NProgress.done()
        })
        .catch((error) => {
          this.description = ''

          if (error === 404) {
            notFound(this)
          } else {
            genericError(this, `Could not navigate to the page "${this.joinedPage}"`)
          }
        })

      if (fetchPages) {
        API.projectRequest(this.project.namespace, '_pages').then((pageList) => {
          this.pages = pageList.pages
        })
      }
    },
    parseDate(rawDate) {
      return new Date(rawDate).toLocaleDateString('default', { year: 'numeric', month: 'long', day: 'numeric' })
    },
    parseCategory(category) {
      return Category.fromId(category).name
    },
    resetPutPage() {
      this.requestPage = {}
    },
    updateCreatePage() {
      const page = this.requestPage

      if (page.name.includes('/')) {
        return
      }

      if (page.parent === 'null') {
        page.parent = null
      }

      let content = null
      if (!page.navigational) {
        content = page.content ? page.content : 'Welcome to your new page'
      }
      let action
      let pageSlug
      if (this.newPage) {
        pageSlug = page.parent ? page.parent + '/' + slugify(page.name) : slugify(page.name)
        action = API.projectRequest(this.project.namespace, '_pages/' + pageSlug, 'PUT', {
          name: page.name,
          content,
        })
      } else {
        pageSlug = page.oldParent ? page.oldParent + '/' + slugify(page.oldName) : slugify(page.oldName)
        action = API.projectRequest(this.project.namespace, '_pages/' + pageSlug, 'PATCH', {
          name: page.name,
          content,
          parent: page.parent,
        })
      }

      action
        .then((res) => {
          const currentJoinedPage = this.joinedPage
          this.$refs.editPageModal.toggle()
          if (currentJoinedPage === pageSlug) {
            // TODO: Route to the created page without data races
            // We should really call updatePage here too, but then we get a 404
            this.$router.push({
              name: 'project_home',
              params: { project: this.project, permissions: this.permissions },
            })
          } else {
            this.resetPutPage()
            this.updatePage(true)
          }
        })
        .catch((err) => {
          // TODO: Better error handling here

          this.pagePutError = err
        })
    },
    savePage(newContent) {
      API.projectRequest(this.project.namespace, '_pages/' + this.joinedPage, 'PUT', {
        name: this.currentPage.name[this.currentPage.name.length - 1],
        content: newContent,
      }).then(() => {
        this.description = newContent
        this.$store.commit({
          type: 'replaceAlert',
          level: 'success',
          message: `[${new Date().toLocaleTimeString()}] Updated the contents of ${this.currentPage.name.join('/')}`,
          tag: 'updatePage',
        })
      })
    },
    deletePage() {
      const page = this.requestPage
      const pageSlug = page.parent ? page.parent + '/' + slugify(page.name) : slugify(page.name)

      API.projectRequest(this.project.namespace, '_pages/' + pageSlug, 'DELETE')
        .then((res) => {
          this.$refs.editPageModal.toggle()
          this.resetPutPage()

          if (pageSlug === this.joinedPage) {
            this.$router.push({
              name: 'project_home',
              params: { project: this.project, permissions: this.permissions },
            })
          } else {
            this.updatePage(true)
          }
        })
        .catch((err) => {
          // TODO: Better error handling here

          this.pagePutError = err
        })
    },
    startEditPage(page) {
      this.$set(this.requestPage, 'existing', true)
      this.$set(this.requestPage, 'oldName', page.name[page.name.length - 1])
      this.$set(this.requestPage, 'name', this.requestPage.oldName)
      this.$set(this.requestPage, 'oldParent', page.slug.length === 1 ? null : page.slug.slice(0, -1).join('/'))
      this.$set(this.requestPage, 'parent', this.requestPage.oldParent)
      this.$set(this.requestPage, 'navigational', page.navigational)

      API.projectRequest(this.project.namespace, '_pages/' + page.slug.join('/'))
        .then((response) => {
          this.$set(this.requestPage, 'content', response.content)
          this.$refs.editPageModal.toggle()
        })
        .catch((error) => {
          if (error === 404) {
            this.$set(this.requestPage, 'existing', undefined)
            this.$refs.editPageModal.toggle()
          } else {
            // TODO
          }
        })
    },
    formatStats(number) {
      return numberWithCommas(number)
    },
  },
}
</script>
