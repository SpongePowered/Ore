<template>
  <div class="tags" :class="{ 'has-addons': data }">
    <span
      :style="{ color: color.foreground, background: color.background, 'border-color': color.background }"
      class="tag"
    >
      {{ name }}
    </span>
    <span v-if="data" class="tag">{{ data }}</span>
  </div>
</template>

<script>
export default {
  props: {
    name: {
      type: String,
      required: true,
    },
    data: {
      type: String,
      default: null,
    },
    color: {
      type: Object,
      required: true,
    },
  },
}
</script>
