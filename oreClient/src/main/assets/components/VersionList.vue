<template>
  <div class="version-list">
    <div class="row text-center">
      <div class="col-xs-12">
        <router-link v-slot="{ href, navigate }" :to="{ name: 'new_version' }">
          <a v-if="canUpload" class="btn yellow" :href="href" @click="navigate">Upload a New Version</a>
        </router-link>
      </div>
    </div>
    <div v-show="loading">
      <FontAwesomeIcon :icon="['fas', 'spinner']" spin />
      <span>Loading versions for you...</span>
    </div>
    <div v-show="!loading">
      <div class="list-group">
        <template v-for="version in versions">
          <div :key="version.name" class="list-group-item" :class="classForVisibility(version.visibility)">
            <div class="container-fluid">
              <div class="row">
                <div class="col-xs-4">
                  <span class="text-bold" style="font-size: 16px">{{ version.name }}</span>
                </div>
                <div class="col-xs-4">
                  <span class="channel" :style="{ background: stabilities.fromId(version.tags.stability).color }">{{
                    stabilities.fromId(version.tags.stability).title
                  }}</span>
                  <span
                    v-if="version.tags.release_type"
                    class="channel"
                    :style="{ background: releaseTypes.fromId(version.tags.release_type).color }"
                    >{{ releaseTypes.fromId(version.tags.release_type).title }}</span
                  >
                </div>
                <div class="col-xs-4">
                  <Tag
                    v-if="version.tags.mixin"
                    name="Mixin"
                    :color="{ background: '#FFA500', foreground: '#333333' }"
                  /><Tag
                    v-for="platform in groupPlatforms(version.tags.platforms)"
                    :key="platform.id + ':' + platform.versions.join('|')"
                    :name="platform.shortName"
                    :data="platform.versions.join('|')"
                    :color="platform.color"
                  />
                </div>
              </div>
              <div class="row">
                <div class="col-xs-4">
                  <div class="row">
                    <div class="col-xs-12">
                      <FontAwesomeIcon :icon="['fas', 'calendar']" fixed-width />
                      <span class="text-bold">Uploaded: </span>
                      {{ formatDate(version.created_at) }}
                    </div>
                    <div class="col-xs-12">
                      <FontAwesomeIcon :icon="['far', 'file']" fixed-width />
                      <span class="text-bold">Size: </span>
                      {{ formatSize(version.file_info.size_bytes) }}
                    </div>
                  </div>
                </div>
                <div class="col-xs-4">
                  <div class="row">
                    <div class="col-xs-12">
                      <FontAwesomeIcon :icon="['fas', 'user-tag']" fixed-width />
                      <span class="text-bold">Author: </span>
                      {{ version.author }}
                    </div>
                    <div class="col-xs-12">
                      <FontAwesomeIcon :icon="['fas', 'download']" fixed-width />
                      <span class="text-bold">Downloads: </span>
                      {{ formatStats(version.stats.downloads) }}
                    </div>
                  </div>
                </div>
                <div class="col-xs-4">
                  <div class="btn-group d-flex d-flex-space-between">
                    <router-link
                      v-slot="{ href, navigate }"
                      :to="{ name: 'version', params: { version: version.name, fetchedVersionObj: version } }"
                    >
                      <a :href="href" class="btn btn-default mb-0" @click="navigate">
                        <FontAwesomeIcon :icon="['fas', 'info-circle']" />
                        Changelog
                      </a>
                    </router-link>
                    <a
                      v-if="project"
                      :href="
                        routes.Versions.download(
                          project.namespace.owner,
                          project.namespace.slug,
                          version.name,
                          null
                        ).absoluteURL()
                      "
                      class="btn btn-primary mb-0"
                      ><FontAwesomeIcon :icon="['fas', 'download']" /> Download</a
                    >
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>

      <Pagination :current="current" :total="total" @prev="page--" @next="page++" @jump-to="page = $event" />
    </div>
  </div>
</template>

<script>
import NProgress from 'nprogress'
import { mapState } from 'vuex'
import filesize from 'filesize'
import { API } from '../api'
import { Platform, ReleaseType, Stability, Visibility } from '../enums'
import { numberWithCommas } from '../utils'
import Pagination from './Pagination'
import Tag from './Tag'

export default {
  components: {
    Tag,
    Pagination,
  },
  props: {
    stability: {
      type: Array,
      required: true,
    },
    platforms: {
      type: Array,
      required: true,
    },
  },
  data() {
    return {
      page: 1,
      limit: 10,
      versions: [],
      totalVersions: 0,
      loading: true,
    }
  },
  computed: {
    canUpload() {
      return this.permissions.includes('create_version')
    },
    routes() {
      return jsRoutes.controllers.project
    },
    offset() {
      return (this.page - 1) * this.limit
    },
    current() {
      return Math.ceil(this.offset / this.limit) + 1
    },
    total() {
      return Math.ceil(this.totalVersions / this.limit)
    },
    stabilities() {
      return Stability
    },
    releaseTypes() {
      return ReleaseType
    },
    ...mapState('project', ['project', 'permissions']),
  },
  watch: {
    page() {
      this.update()
      window.scrollTo(0, 0)
    },
    stability() {
      this.page = 1
      this.update()
      window.scrollTo(0, 0)
    },
    platforms() {
      this.page = 1
      this.update()
      window.scrollTo(0, 0)
    },
    project: {
      handler(val, oldVal) {
        // eslint-disable-next-line camelcase
        if (val && val.plugin_id !== oldVal?.plugin_id) {
          this.update()
        }
      },
      immediate: true,
    },
  },
  methods: {
    update() {
      const requestParams = {
        limit: this.limit,
        offset: this.offset,
        platforms: this.platforms,
        stability: this.stability,
      }

      NProgress.start()
      API.projectRequest(this.project.namespace, 'versions', 'GET', requestParams).then((response) => {
        this.versions = response.result
        this.totalVersions = response.pagination.count
        this.loading = false
        NProgress.done()
      })
    },
    formatSize(size) {
      return filesize(size)
    },
    formatDate(date) {
      return new Date(date).toLocaleDateString('default', { year: 'numeric', month: 'long', day: 'numeric' })
    },
    classForVisibility(visibility) {
      return Visibility.fromName(visibility).class
    },
    groupPlatforms(platforms) {
      const versions = {}

      for (const platform of platforms) {
        if (typeof versions[platform.platform] === 'undefined') {
          versions[platform.platform] = []
        }

        if (platform.display_platform_version) {
          versions[platform.platform].push(platform.display_platform_version)
        } else {
          versions[platform.platform].push(platform.platform_version)
        }
      }

      const platformObjs = []

      for (const platform in versions) {
        if (Object.prototype.hasOwnProperty.call(versions, platform)) {
          const obj = Platform.fromId(platform)

          platformObjs.push({
            id: platform,
            shortName: obj.shortName,
            versions: versions[platform],
            color: obj.color,
          })
        }
      }

      return platformObjs
    },
    formatStats(number) {
      return numberWithCommas(number)
    },
  },
}
</script>
