<template>
  <div>
    <div class="input-group input-group-sm">
      <input v-model="query" type="text" class="form-control" placeholder="Add User…" />
    </div>
    <div v-if="users.length" class="open">
      <ul class="dropdown-menu">
        <li v-for="user in users" :key="user.name">
          <a
            href="#"
            @click="
              () => {
                $emit('add-user', user)
                reset()
              }
            "
          >
            <icon :src="avatarUrl(user.name)" extra-classes="user-avatar-xs" />
            {{ user.name }}
          </a>
        </li>
      </ul>
    </div>
  </div>
</template>

<script>
import debounce from 'lodash/debounce'
import { API } from '../api'
import { avatarUrl } from '../utils'
import Icon from './Icon'

export default {
  components: {
    Icon,
  },
  props: {
    exclude: {
      type: Array,
      default() {
        return []
      },
    },
    excludeOrganizations: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      query: '',
      users: [],
    }
  },
  watch: {
    query() {
      this.updateUser()
    },
  },
  methods: {
    updateUser: debounce(function () {
      if (this.query === '') {
        this.users = []
      } else {
        API.request('users', 'GET', {
          q: this.query,
          limit: 5,
          excludeOrganizations: this.excludeOrganizations,
        }).then((res) => {
          this.users = res.result.filter((u) => !this.exclude.includes(u.name))
        })
      }
    }, 250),
    avatarUrl(name) {
      return avatarUrl(name)
    },
    reset() {
      this.query = ''
      this.users = []
    },
  },
}
</script>
