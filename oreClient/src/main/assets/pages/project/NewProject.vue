<template>
  <div class="row">
    <div class="col-xs-12">
      <div class="panel panel-default">
        <div class="panel-heading">
          <h3 class="panel-title">Create a new project</h3>
        </div>

        <div v-if="memberships && currentUser" class="panel-body project-body">
          <div class="minor create-blurb">
            <p>A project contains your downloads and the documentation for your plugin.</p>
            <p>
              Before continuing, please review the
              <a href="https://docs.spongepowered.org/stable/en/ore/guidelines.html">Ore Submission Guidelines</a>
            </p>
          </div>

          <div>
            <div class="form-group">
              <label for="projectName">Project name</label>
              <input
                id="projectName"
                v-model.trim="projectName"
                type="text"
                name="name"
                class="form-control"
                required
              />
            </div>

            <div class="form-group">
              <label for="projectPluginId">Plugin id</label>
              <input
                id="projectPluginId"
                v-model.trim="pluginId"
                type="text"
                name="pluginId"
                class="form-control"
                required
              />
            </div>

            <div class="form-group">
              <label for="projectCategory">Project category</label>
              <select id="projectCategory" v-model="category" class="form-control" required>
                <option v-for="categoryIt in categories.values" :key="categoryIt.id" :value="categoryIt.id">
                  {{ categoryIt.name }}
                </option>
              </select>
            </div>

            <div class="form-group">
              <label for="projectDescription">Project description</label>
              <input
                id="projectDescription"
                v-model.trim="projectDescription"
                type="text"
                name="description"
                class="form-control"
              />
            </div>

            <div class="form-group">
              <label for="projectOwner">Owner</label>
              <select id="projectOwner" v-model="owner" name="owner" class="form-control" required>
                <option :value="currentUser.name">
                  {{ currentUser.name }}
                </option>
                <option
                  v-for="membership in availableOwners"
                  :key="membership.organization.name"
                  :value="membership.organization.name"
                >
                  {{ membership.organization.name }}
                </option>
              </select>
            </div>

            <p class="minor create-blurb">Make sure everything is correct before creating your project.</p>

            <button class="btn btn-primary" @click="create()">Create project</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { Category, Permission } from '../../enums'
import { API } from '../../api'

export default {
  data() {
    return {
      projectName: '',
      pluginId: '',
      projectDescription: '',
      category: null,
      owner: '',
      availableOwners: [],
    }
  },
  computed: {
    categories() {
      return Category
    },
    ...mapState('global', ['currentUser', 'memberships']),
  },
  watch: {
    memberships: {
      handler(newVal, oldVal) {
        this.updateOwners(newVal)
      },
      immediate: true,
    },
  },
  methods: {
    create() {
      let error = false
      const messages = []

      this.$store.commit({
        type: 'dismissAlertsByType',
        level: 'error',
      })

      if (!this.projectName || this.projectName === '') {
        messages.push('Project name is required')
        error = true
      }
      if (!this.pluginId || this.pluginId === '') {
        messages.push('Plugin id is required')
        error = true
      }
      if (!this.category) {
        messages.push('Category is required')
        error = true
      }
      if (!this.owner || this.owner === '') {
        messages.push('Owner is required')
        error = true
      }

      if (error) {
        this.$store.commit({
          type: 'addAlerts',
          level: 'error',
          messages,
        })
      } else {
        API.request('projects', 'POST', {
          name: this.projectName,
          plugin_id: this.pluginId,
          category: this.category,
          description: this.projectDescription,
          owner_name: this.owner,
        }).then((data) => {
          this.$router.push({ name: 'project_home', params: { fetchedProject: data, ...data.namespace } })
        })
      }
    },
    updateOwners(all) {
      this.availableOwners = []

      all
        .filter((m) => m.scope === 'organization')
        .forEach((o) => {
          API.request('permissions/hasAny', 'GET', {
            permissions: [Permission.CreateProject],
            organizationName: o.organization.name,
          }).then((res) => {
            if (res.result === true) {
              this.availableOwners.push(o)
            }
          })
        })
    },
  },
}
</script>
