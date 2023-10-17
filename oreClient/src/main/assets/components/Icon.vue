<template>
  <router-link v-if="name" :to="{ name: 'user_projects', params: { user: name } }">
    <img class="user-avatar" :class="extraClasses" :src="src" :alt="name" />
  </router-link>
  <a v-else :href="linkUrl">
    <img class="user-avatar" :class="extraClasses" :src="src" :alt="name" />
  </a>
</template>

<script>
export default {
  props: {
    src: {
      type: String,
      required: true,
    },
    href: {
      type: String,
      default: null,
    },
    extraClasses: {
      type: String,
      default: '',
    },
    name: {
      type: String,
      default: null,
    },
  },
  computed: {
    linkUrl() {
      if (this.href != null) {
        return this.href
      } else {
        return '#'
      }
    },
  },
}
</script>
