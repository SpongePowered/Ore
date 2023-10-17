<template>
  <select :value="value" @input="$emit('input', $event.target.value)">
    <option v-for="roleType in roles" :key="roleType.name" :value="roleType.name">
      {{ roleType.title }}
    </option>
  </select>
</template>

<script>
import { Role } from '../enums'

export default {
  props: {
    value: {
      type: String,
      required: true,
    },
    roleCategory: {
      type: String,
      required: true,
    },
  },
  computed: {
    roles() {
      return Role.categoryRoles(this.roleCategory).filter((role) => role.isAssignable)
    },
  },
}
</script>
