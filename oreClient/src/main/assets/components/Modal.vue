<template>
  <div :id="'modal-' + name" class="modal fade" tabindex="-1" role="dialog" :aria-labelledby="'label-' + name">
    <div class="modal-dialog" role="document">
      <div class="modal-content">
        <div class="modal-header">
          <button type="button" class="close" data-dismiss="modal" aria-label="Cancel" @click="onClose">
            <span aria-hidden="true">&times;</span>
          </button>
          <h4 :id="'label-' + name" class="modal-title" style="color: black">
            {{ title }}
          </h4>
        </div>
        <div class="modal-body">
          <slot></slot>
        </div>
        <div class="modal-footer">
          <div v-if="onSubmit" class="form-inline">
            <button type="button" class="btn btn-default" data-dismiss="modal" @click="onClose">Close</button>
            <button name="rename" class="btn btn-warning" @click="onSubmit">
              {{ buttonLabel }}
            </button>
          </div>
          <slot v-else name="footer"></slot>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    name: {
      type: String,
      required: true,
    },
    title: {
      type: String,
      required: true,
    },
    buttonLabel: {
      type: String,
      default: 'Submit',
    },
    // eslint-disable-next-line vue/require-default-prop
    onSubmit: Function,
    onClose: {
      type: Function,
      default: () => {},
    },
  },
  methods: {
    toggle() {
      $('#modal-' + this.name).modal('toggle')
    },
    show() {
      $('#modal-' + this.name).modal('show')
    },
    hide() {
      $('#modal-' + this.name).modal('hide')
    },
  },
}
</script>
