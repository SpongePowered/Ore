<template>
  <div v-if="enabled">
    <!-- Edit -->
    <button
      type="button"
      class="btn btn-sm btn-edit btn-page btn-default"
      :class="editClasses"
      title="Edit"
      @click="state = 'edit'"
    >
      <FontAwesomeIcon :icon="['fas', 'edit']" /> Edit
    </button>

    <!-- Preview -->
    <div class="btn-edit-container btn-preview-container" :class="buttonStateClasses" title="Preview">
      <button
        v-if="state !== 'display'"
        type="button"
        class="btn btn-sm btn-preview btn-page btn-default"
        :class="previewClasses"
        @click="state = 'preview'"
      >
        <FontAwesomeIcon :icon="['fas', 'eye']" />
      </button>
    </div>

    <!-- Save -->
    <div v-if="savable" class="btn-edit-container btn-save-container" :class="buttonStateClasses" title="Save">
      <button class="btn btn-sm btn-save btn-page btn-default" @click="$emit('saved', rawData)">
        <FontAwesomeIcon :icon="['fas', 'save']" />
      </button>
    </div>

    <!-- Cancel -->
    <div v-if="cancellable" class="btn-edit-container btn-cancel-container" :class="buttonStateClasses" title="Cancel">
      <button type="button" class="btn btn-sm btn-cancel btn-page btn-default" @click="reset">
        <FontAwesomeIcon :icon="['fas', 'times']" />
      </button>
    </div>

    <!-- Delete -->
    <template v-if="deletable">
      <div class="btn-edit-container btn-delete-container" :class="buttonStateClasses" title="Delete">
        <button
          type="button"
          class="btn btn-sm btn-page-delete btn-page btn-default"
          data-toggle="modal"
          data-target="#modal-page-delete"
        >
          <FontAwesomeIcon :icon="['fas', 'trash']" />
        </button>
      </div>

      <modal name="editor-delete" :title="'Delete ' + subject.toLocaleLowerCase()" :on-submit="() => $emit('delete')">
        Are you sure you want to delete this {{ subject.toLowerCase() }}? This cannot be undone.
      </modal>
    </template>

    <div v-if="state === 'edit'" class="page-edit">
      <textarea v-model="rawData" name="content" class="form-control" @change="$emit('change', rawData)" />
    </div>
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-else-if="state === 'preview'" class="page-preview page-rendered" v-html="previewCooked" />
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div v-else-if="state === 'display'" class="page-content page-rendered" v-html="cooked" />
  </div>
  <div v-else>
    <!-- Saved window -->
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="page-content page-rendered" v-html="cooked" />
  </div>
</template>

<script>
import markdownIt from 'markdown-it'
import markdownItAnchor from 'markdown-it-anchor'
//import markdownItWikilinks from 'markdown-it-wikilinks' //TODO: Find/make an replacement
import markdownItTaskLists from 'markdown-it-task-lists'
import Modal from './Modal'

export default {
  components: {
    Modal,
  },
  props: {
    savable: {
      type: Boolean,
      default: true,
    },
    deletable: {
      type: Boolean,
      default: false,
    },
    enabled: {
      type: Boolean,
      required: true,
    },
    cancellable: {
      type: Boolean,
      default: true,
    },
    raw: {
      type: String,
      default: '',
    },
    subject: {
      type: String,
      default: '',
    },
    markdownBaseUrl: {
      type: String,
      required: true,
    },
    markdownRelativeBaseUrl: {
      type: String,
      required: true,
    },
  },
  data() {
    return {
      state: 'display',
      rawData: this.raw,
    }
  },
  computed: {
    md() {
      const mdInstance = markdownIt({
        html: true,
        linkify: true,
        typographer: true,
      })
        .use(markdownItAnchor)
        /*.use(
          markdownItWikilinks({
            baseUrl: this.markdownBaseUrl,
            relativeBaseURL: this.markdownRelativeBaseUrl,
            uriSuffix: '',
          })
        )*/
        .use(markdownItTaskLists)

      mdInstance.linkify.set({})

      return mdInstance
    },
    previewCooked() {
      return this.md.render(this.rawData)
    },
    cooked() {
      return this.md.render(this.raw)
    },
    buttonStateClasses() {
      return {
        'btn-editor-display': this.state === 'display',
        'btn-editor-active': this.state !== 'display',
      }
    },
    editClasses() {
      return {
        'editor-tab-active': this.state === 'edit',
        'editor-tab-inactive': this.state !== 'edit',
        ...this.buttonStateClasses,
      }
    },
    previewClasses() {
      return {
        'editor-tab-active': this.state === 'preview',
        'editor-tab-inactive': this.state !== 'preview',
      }
    },
  },
  watch: {
    raw(newVal, oldVal) {
      this.rawData = newVal
    },
  },
  methods: {
    reset() {
      this.rawData = this.raw
      this.state = 'display'
    },
  },
}
</script>
