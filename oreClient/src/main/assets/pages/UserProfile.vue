<template>
  <ProjectList
    :owner="user"
    :offset="(page - 1) * limit"
    :limit="limit"
    @prev-page="page--"
    @next-page="page++"
    @jump-to-page="page = $event"
  />
</template>

<script>
import ProjectList from './../components/ProjectList'

export default {
  components: {
    ProjectList,
  },
  props: {
    user: {
      type: String,
      required: true,
    },
  },
  data() {
    return {
      page: 1,
      limit: 5,
    }
  },
}
</script>
