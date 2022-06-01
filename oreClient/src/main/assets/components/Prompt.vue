<template>
  <div class="prompt popover" :class="[idClass, position]">
    <div class="arrow" />
    <h3 class="popover-title">
      {{ prompt.title }}
    </h3>
    <div class="popover-content">
      <p>{{ prompt.message }}</p>
      <button class="btn btn-success btn-sm" @click="acknowledgePrompt">Got it!</button>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    prompt: {
      type: Object,
      required: true,
    },
    idClass: {
      type: String,
      default: '',
    },
    position: {
      type: String,
      default: 'right',
    },
  },
  methods: {
    acknowledgePrompt() {
      $.ajax({
        type: 'post',
        url: jsRoutes.controllers.Users.markPromptRead(prompt.id).absoluteURL(),
      })
      $('.prompt').fadeOut('fast')
    },
  },
}
</script>
