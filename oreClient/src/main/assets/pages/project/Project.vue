<template>
  <div>
    <project-header />
    <router-view />
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { notFound } from '../../utils'
import ProjectHeader from './../../components/ProjectHeader'

export default {
  components: {
    ProjectHeader,
  },
  props: {
    owner: {
      type: String,
      default: null,
    },
    slug: {
      type: String,
      default: null,
    },
    fetchedProject: {
      type: Object,
      required: false,
      default: null,
    },
  },
  computed: {
    ...mapState('project', ['notFound']),
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
      if (
        this.fetchedProject &&
        this.fetchedProject.namespace.owner === this.owner &&
        this.fetchedProject.namespace.slug === this.slug
      ) {
        this.$store.dispatch('project/setActiveProjectFromFetched', this.fetchedProject)
      } else {
        this.$store.dispatch('project/setActiveProject', { owner: this.owner, slug: this.slug })
      }
    },
  },
}
</script>
