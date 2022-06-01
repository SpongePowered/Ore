<template>
  <div class="btn-group">
    <button
      id="visibility-actions"
      class="btn btn-sm btn-alert btn-hide-dropdown dropdown-toggle"
      type="button"
      data-toggle="dropdown"
      aria-haspopup="true"
      aria-expanded="false"
      style="color: black"
    >
      <FontAwesomeIcon :icon="['fas', spinIcon ? 'spinner' : 'eye']" :spin="spinIcon" />
      Visibility actions
      <span class="caret" />
    </button>
    <ul class="dropdown-menu" aria-labelledby="visibility-actions">
      <li v-for="visibility in visibilities.values" :key="visibility.name">
        <a href="#" class="btn-visibility-change" @click="handleVisibilityClick(visibility)">
          {{ visibilityMessage(visibility.name) }}
          <FontAwesomeIcon
            v-if="currentVisibility === visibility.name"
            :icon="['fas', 'check']"
            style="color: black"
            aria-hidden="true"
          />
        </a>
      </li>
    </ul>

    <modal ref="commentModal" name="visibility-comment" title="Comment" :on-close="resetData">
      <textarea v-model="comment" class="textarea-visibility-comment form-control" rows="3" />

      <template #footer>
        <button class="btn btn-default" data-dismiss="modal" @click="resetData">Close</button>
        <button class="btn btn-visibility-comment-submit btn-primary" @click="sendVisibilityChange(selectedVisibility)">
          <FontAwesomeIcon :icon="['fas', 'pencil-alt']" />
          Submit
        </button>
      </template>
    </modal>
  </div>
</template>

<script>
import { Visibility } from '../enums'
import { API } from '../api'
import Modal from './Modal'

export default {
  components: {
    Modal,
  },
  props: {
    currentVisibility: {
      type: String,
      required: true,
    },
    endpoint: {
      type: String,
      required: true,
    },
    commitLocation: {
      type: String,
      default: null,
    },
    callback: {
      type: Function,
      default: null,
    },
  },
  data() {
    return {
      selectedVisibility: null,
      comment: '',
      spinIcon: false,
    }
  },
  computed: {
    visibilities() {
      return Visibility
    },
  },
  methods: {
    resetData() {
      this.selectedVisibility = null
      this.comment = ''
    },
    visibilityMessage(visibility) {
      switch (visibility) {
        case 'public':
          return 'Public'
        case 'new':
          return 'New'
        case 'needsChanges':
          return 'Needs changes'
        case 'needsApproval':
          return 'Needs approval'
        case 'softDelete':
          return 'Soft deleted'
      }
    },
    handleVisibilityClick(visibility) {
      if (visibility.showModal) {
        this.selectedVisibility = visibility
        this.$refs.commentModal.show()
      } else {
        this.sendVisibilityChange(visibility)
      }
    },
    sendVisibilityChange(visibility) {
      this.spinIcon = true
      API.request(this.endpoint, 'POST', {
        visibility: visibility.name,
        comment: this.comment,
      }).then((res) => {
        this.spinIcon = false
        this.$refs.commentModal.hide()

        if (this.commitLocation) {
          this.$store.commit({
            type: this.commitLocation,
            visibility: visibility.name,
          })
        }

        if (this.callback) {
          this.callback(visibility.name)
        }
      })
    },
  },
}
</script>
