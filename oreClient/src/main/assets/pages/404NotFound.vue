<template>
  <div class="container-error container">
    <img src="../images/ore-dark.png" alt="Ore logo" />
    <h1 class="minor">404 Not Found</h1>
    <h3 class="minor">The URI "/{{ routeUri }}" could not be found</h3>
  </div>
</template>

<script>
export default {
  props: {
    routeUri: {
      type: String,
      default: '/',
    },
  },
}
</script>
