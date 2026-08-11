<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'

const THEME_KEY = 'evocode-theme'
const saved = localStorage.getItem(THEME_KEY)
if (saved === 'dark') {
  document.documentElement.dataset.theme = 'dark'
}
const isDark = ref(document.documentElement.dataset.theme === 'dark')

function toggleTheme() {
  const next = isDark.value ? '' : 'dark'
  document.documentElement.dataset.theme = next
  localStorage.setItem(THEME_KEY, next)
  isDark.value = next === 'dark'
}
</script>

<template>
  <header class="app-header">
    <span class="app-logo">Evo<span class="app-logo-sub">Code</span></span>
    <nav class="app-nav">
      <RouterLink to="/dashboard" class="app-nav-link">Dashboard</RouterLink>
      <RouterLink to="/projects" class="app-nav-link">项目</RouterLink>
    </nav>
    <span class="app-slogan">AI 软件体检与演化平台</span>
    <button
      class="app-theme"
      type="button"
      :title="isDark ? '切换浅色' : '切换深色'"
      @click="toggleTheme"
    >
      {{ isDark ? '☀️' : '🌙' }}
    </button>
  </header>
  <main class="app-main">
    <RouterView v-slot="{ Component }">
      <Transition name="fade" mode="out-in">
        <component :is="Component" />
      </Transition>
    </RouterView>
  </main>
</template>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 24px;
  border-bottom: 1px solid var(--border-color);
  background: color-mix(in srgb, var(--bg-card) 88%, transparent);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  position: sticky;
  top: 0;
  z-index: 50;
}
.app-logo {
  font-size: 20px;
  font-weight: 700;
  color: var(--primary-color);
  letter-spacing: 0.02em;
}
.app-logo-sub {
  font-weight: 300;
  color: var(--text-secondary);
}
.app-nav {
  display: flex;
  gap: 4px;
}
.app-nav-link {
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-secondary);
  text-decoration: none;
  position: relative;
  transition: color var(--transition);
}
.app-nav-link:hover {
  color: var(--text-primary);
}
.app-nav-link.router-link-active {
  color: var(--primary-color);
  font-weight: 600;
}
/* P9a：active 下划线指示 */
.app-nav-link.router-link-active::after {
  content: '';
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 0;
  height: 2px;
  border-radius: 1px;
  background: var(--primary-color);
}
.app-slogan {
  flex: 1;
  font-size: 13px;
  color: var(--text-secondary);
}
.app-theme {
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  border-radius: var(--radius-sm);
  padding: 4px 10px;
  font-size: 14px;
  cursor: pointer;
  transition: border-color var(--transition);
}
.app-theme:hover {
  border-color: var(--primary-color);
}
.app-main {
  padding: 20px 24px;
  max-width: 1200px;
  margin: 0 auto;
}
</style>
