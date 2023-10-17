<template>
  <input type="hidden" name="csrfToken" :value="windowObj.csrf" />
</template>

<script>
export default {
  computed: {
    windowObj() {
      return window
    },
  },
}
</script>
