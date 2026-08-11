<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <header class="app-header">
    <span class="app-logo">EvoCode</span>
    <span class="app-slogan">AI 软件体检与演化平台</span>
  </header>
  <main class="app-main">
    <RouterView />
  </main>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border-color);
}
.app-logo {
  font-size: 20px;
  font-weight: 700;
  color: var(--primary-color);
}
.app-slogan {
  font-size: 13px;
  color: var(--text-secondary);
}
.app-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
}
</style>
