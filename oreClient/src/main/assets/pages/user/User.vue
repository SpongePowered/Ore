<template>
  <div>
    <user-header />
    <router-view />
  </div>
</template>

<script>
import { mapState } from 'vuex'
import UserHeader from '../../components/UserHeader'
import { notFound } from '../../utils'

export default {
  components: {
    UserHeader,
  },
  props: {
    user: {
      type: String,
      required: true,
    },
  },
  computed: {
    ...mapState('user', ['notFound']),
  },
  watch: {
    $route: {
      handler() {
        this.distpatchUpdate()
      },
      immediate: true,
    },
    notFound(val) {
      if (val) {
        notFound(this)
      }
    },
  },
  methods: {
    distpatchUpdate() {
      this.$store.dispatch('user/setActiveUser', this.user)
    },
  },
}
</script>
