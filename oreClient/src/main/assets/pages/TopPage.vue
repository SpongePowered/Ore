<template>
  <div class="site">
    <top-header />

    <div class="site-content">
      <div class="container site-header-margin">
        <div v-if="hasAnyAlerts" class="row">
          <div class="col-xs-12">
            <div v-for="level of levels" :key="level">
              <div
                v-if="alerts[level].length"
                class="alert alert-fade alert-dismissable"
                :class="levelToClass(level)"
                role="alert"
              >
                <button
                  type="button"
                  class="close"
                  data-dismiss="alert"
                  aria-label="Close"
                  @click="$store.commit('dismissAlertsByType', { level })"
                >
                  <span aria-hidden="true">&times;</span>
                </button>

                <span v-if="alerts[level].length === 1">{{ alerts[level][0].message }}</span>
                <ul v-else-if="alerts[level].length">
                  <li v-for="(alert, index) in alerts[level]" :key="index">
                    {{ alert.message }}
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <router-view />
      </div>
    </div>

    <top-footer />
  </div>
</template>

<script>
import { mapState } from 'vuex'
import TopHeader from '../components/TopHeader'
import TopFooter from '../components/TopFooter'

const levelClasses = {
  error: 'alert-danger',
  success: 'alert-success',
  info: 'alert-info',
  warning: 'alert-warning',
}

export default {
  components: {
    TopHeader,
    TopFooter,
  },
  computed: {
    levels() {
      return ['error', 'success', 'info', 'warning']
    },
    hasAnyAlerts() {
      return this.levels.some((level) => this.alerts[level].length)
    },
    ...mapState(['alerts']),
  },
  methods: {
    levelToClass(level) {
      return levelClasses[level]
    },
  },
}
</script>
