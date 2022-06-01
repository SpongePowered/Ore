<template>
  <li class="list-group-item">
    <a
      v-if="page.children"
      :class="expandedChildren ? 'page-collapse' : 'page-expand'"
      @click="expandedChildren = !expandedChildren"
    >
      <FontAwesomeIcon :icon="['far', expandedChildren ? 'minus-square' : 'plus-square']" />
    </a>
    <router-link
      v-if="!page.navigational"
      v-slot="{ href, navigate }"
      :to="{ name: 'pages', params: { page: page.slug } }"
    >
      <a :href="href" @click="navigate">{{ page.name[page.name.length - 1] }}</a>
    </router-link>
    <span v-else>
      {{ page.name[page.name.length - 1] }}
    </span>

    <div v-if="permissions.includes('edit_page')" class="pull-right">
      <a href="#" @click="$emit('edit-page', page)">
        <FontAwesomeIcon style="padding-left: 5px" :icon="['fas', 'edit']" />
      </a>
    </div>

    <page-list
      v-if="page.children && expandedChildren"
      :pages="page.children"
      @edit-page="(event) => $emit('edit-page', event)"
    />
  </li>
</template>

<script>
import { mapState } from 'vuex'

export default {
  components: {
    'page-list': () => import('./PageList'),
  },
  props: {
    page: {
      type: Object,
      required: true,
    },
  },
  data() {
    return {
      expandedChildren: false,
    }
  },
  computed: mapState('project', ['permissions']),
}
</script>
