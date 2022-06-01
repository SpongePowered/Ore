<template>
  <ul class="list-group" style="margin-bottom: 0px">
    <li v-if="includeHome" class="list-group-item">
      <router-link v-slot="{ href, navigate }" :to="{ name: 'project_home' }">
        <a :href="href" @click="navigate">Home</a>
      </router-link>
    </li>

    <li
      is="page-link"
      v-for="page in pages"
      :key="page.slug.join('/')"
      :page="page"
      @edit-page="(event) => $emit('edit-page', event)"
    />
  </ul>
</template>

<script>
import PageLink from './PageLink'

export default {
  components: {
    PageLink,
  },
  props: {
    includeHome: {
      type: Boolean,
      default: false,
    },
    pages: {
      type: Object,
      required: true,
    },
  },
}
</script>
