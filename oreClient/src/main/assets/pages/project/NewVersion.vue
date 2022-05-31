<template>
  <div>
    <div class="panel panel-default">
      <div class="panel-heading">
        <h3 class="panel-title">New project release</h3>
      </div>

      <div class="panel-body">
        <div class="row">
          <div class="col-md-4"></div>
          <div class="col-md-4">
            <div v-if="!processing && versionPreview" class="mb-1 text-center">
              {{ this.$refs.fileInput.files[0].name }}
            </div>
            <label class="btn btn-primary btn-block" :class="{ 'btn-lg': !processing && !versionPreview }">
              <input
                ref="fileInput"
                type="file"
                style="display: none;"
                :disabled="processing"
                accept=".jar, application/zip"
                @change="processFileUpload"
              />
              <span v-if="!processing && !versionPreview">
                Select file
              </span>
              <span v-else-if="!versionPreview">
                Processing <font-awesome-icon :icon="['fas', 'spinner']" spin />
              </span>
              <span v-else>
                Upload a different file
              </span>
            </label>
          </div>
          <div class="col-md-4"></div>
        </div>
      </div>
    </div>

    <div v-if="versionPreview" class="panel panel-default">
      <div v-if="versionPreview" class="panel-heading">
        <h3 class="panel-title">Version info</h3>
      </div>

      <div v-if="versionPreview" class="panel-body">
        <div class="form-horizontal">
          <div class="form-group">
            <label class="col-sm-2 control-label">Name:</label>
            <div class="col-sm-10">
              <p class="form-control-static">{{ versionPreview.name }}</p>
            </div>
          </div>
          <div class="form-group">
            <label for="createForumPost" class="col-sm-2 control-label">Create forum post:</label>
            <div class="col-sm-10">
              <input id="createForumPost" v-model="createForumPost" type="checkbox" class="form-control-static" />
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-2 control-label">File name:</label>
            <div class="col-sm-10">
              <p class="form-control-static">{{ versionPreview.file_info.name }}</p>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-2 control-label">File size:</label>
            <div class="col-sm-10">
              <p class="form-control-static">{{ formatBytes(versionPreview.file_info.size_bytes) }}</p>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-2 control-label">File hash:</label>
            <div class="col-sm-10">
              <p class="form-control-static">{{ versionPreview.file_info.md5_hash }}</p>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-2 control-label">Mixin:</label>
            <div class="col-sm-10">
              <p class="form-control-static">{{ versionPreview.tags.mixin }}</p>
            </div>
          </div>
          <div class="form-group">
            <label for="stabilitySelect" class="col-sm-2 control-label">Stability:</label>
            <div class="col-sm-10">
              <select id="stabilitySelect" v-model="selectedStability" class="form-control">
                <option v-for="stability in stabilities" :key="stability.id" :value="stability.id">
                  {{ stability.title }}
                </option>
              </select>
            </div>
          </div>
          <div class="form-group">
            <label for="releaseTypeSelect" class="col-sm-2 control-label">Release type:</label>
            <div class="col-sm-10">
              <select id="releaseTypeSelect" v-model="selectedReleaseType" class="form-control">
                <option :value="null">None</option>
                <option v-for="releaseType in releaseTypes" :key="releaseType.id" :value="releaseType.id">
                  {{ releaseType.title }}
                </option>
              </select>
            </div>
          </div>
          <div class="form-group">
            <label class="col-sm-2 control-label">Platforms:</label>
            <div class="col-sm-10">
              <ul class="list-unstyled">
                <li
                  v-for="platform in versionPreview.tags.platforms"
                  :key="platform.platform + platform.platform_version"
                >
                  {{ platforms.fromId(platform.platform).shortName }} ({{ platform.platform }}):
                  {{ platform.platform_version }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-if="versionPreview" class="panel panel-default">
      <div v-if="versionPreview" class="panel-heading">
        <h3 class="panel-title">Version description</h3>
      </div>
      <div v-if="versionPreview" class="panel-body">
        <editor
          :enabled="true"
          :raw="description"
          :markdown-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/`"
          :markdown-relative-base-url="`/${project.namespace.owner}/${project.namespace.slug}/pages/`"
          @change="(data) => (description = data)"
          @saved="(data) => (description = data)"
        ></editor>

        <button class="btn btn-success" @click="publishVersion">
          <span v-if="!uploadProcessing">Publish version</span>
          <span v-else> Processing <font-awesome-icon :icon="['fas', 'spinner']" spin /> </span>
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { API } from '../../api'
import { Platform, ReleaseType, Stability } from '../../enums'
import Editor from '../../components/Editor'

export default {
  components: { Editor },
  data() {
    return {
      processing: false,
      uploadProcessing: false,
      versionPreview: null,
      createForumPost: this.project?.settings?.forumSync ?? true,
      selectedStability: null,
      selectedReleaseType: null,
      description: '',
    }
  },
  computed: {
    stabilities() {
      return Stability.values
    },
    releaseTypes() {
      return ReleaseType.values
    },
    platforms() {
      return Platform
    },
    ...mapState('project', ['project', 'permissions']),
  },
  methods: {
    // https://stackoverflow.com/a/18650828/7207457
    formatBytes(bytes, decimals = 2) {
      if (bytes === 0) {
        return '0 Bytes'
      }

      const k = 1024
      const dm = decimals < 0 ? 0 : decimals
      const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']

      const i = Math.floor(Math.log(bytes) / Math.log(k))

      return parseFloat((bytes / k ** i).toFixed(dm)) + ' ' + sizes[i]
    },
    async processFileUpload() {
      if (this.$refs.fileInput.files.length !== 1 && this.versionPreview) {
        this.processing = false
        this.versionPreview = false
        return
      }

      this.processing = true
      this.$store.commit('dismissAllAlerts')

      const formData = new FormData()
      formData.append('plugin-file', this.$refs.fileInput.files[0])

      try {
        const scanResult = await API.projectRequest(this.project.namespace, 'versions/scan', 'PUT', formData)
        this.versionPreview = scanResult.version
        this.selectedStability = this.versionPreview.tags.stability
        this.selectedReleaseType = this.versionPreview.tags.release_type
        this.description = '# ' + this.versionPreview.name + '\nWelcome to your new version'
        this.createForumPost = this.project.settings.forumSync

        if (scanResult.warnings) {
          this.$store.commit({
            type: 'addAlerts',
            level: 'warning',
            messages: scanResult.warnings,
          })
        }
      } catch {
        // The API class should handle setting errors
        // NO-OP
      } finally {
        this.processing = false
      }
    },
    async publishVersion() {
      this.uploadProcessing = true

      const formData = new FormData()
      formData.append('plugin-file', this.$refs.fileInput.files[0])

      const pluginInfo = {
        create_forum_post: this.createForumPost,
        description: this.description,
        stability: this.selectedStability,
        release_type: this.selectedReleaseType ?? undefined,
      }

      formData.append('plugin-info', JSON.stringify(pluginInfo))

      try {
        const deployResult = await API.projectRequest(this.project.namespace, 'versions', 'POST', formData)

        this.$router.push({ name: 'version', params: { version: deployResult.name, fetchedVersionObj: deployResult } })
      } catch {
        // The API class should handle setting errors
        // NO-OP
      } finally {
        this.uploadProcessing = false
      }
    },
  },
}
</script>
