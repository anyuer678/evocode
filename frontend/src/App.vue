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
  <div class="app-shell">
    <header class="app-header">
      <div class="app-brand">
        <span class="app-logo">EvoCode</span>
        <span class="app-tagline">AI 软件体检与演化平台</span>
      </div>
      <nav class="app-nav" aria-label="主导航">
        <RouterLink to="/dashboard" class="app-nav-link">健康总览</RouterLink>
        <RouterLink to="/projects" class="app-nav-link">项目档案</RouterLink>
      </nav>
      <button
        class="app-theme"
        type="button"
        :title="isDark ? '切换浅色' : '切换深色'"
        @click="toggleTheme"
      >
        {{ isDark ? '浅色' : '深色' }}
      </button>
    </header>
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <Transition name="fade" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  height: 52px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-card);
  position: sticky;
  top: 0;
  z-index: 50;
  flex-shrink: 0;
}
.app-brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.app-logo {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: 0.04em;
  white-space: nowrap;
}
.app-tagline {
  font-size: 12px;
  color: var(--text-tertiary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.app-nav {
  display: flex;
  gap: 2px;
}
.app-nav-link {
  padding: 6px 12px;
  border-radius: 2px;
  font-size: 13px;
  color: var(--text-secondary);
  text-decoration: none;
  transition:
    color var(--transition),
    background var(--transition);
}
.app-nav-link:hover {
  color: var(--text-primary);
  background: var(--bg-muted);
}
.app-nav-link.router-link-active {
  color: var(--primary-color);
  font-weight: 600;
}
.app-theme {
  margin-left: auto;
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  border-radius: 2px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color var(--transition);
}
.app-theme:hover {
  border-color: var(--primary-color);
}
.app-main {
  flex: 1;
  padding: 20px 24px 48px;
  width: 100%;
}

@media (max-width: 720px) {
  .app-header {
    padding: 0 12px;
    gap: 10px;
  }
  .app-tagline {
    display: none;
  }
  .app-nav-link {
    padding: 6px 8px;
  }
  .app-main {
    padding: 12px 10px 40px;
  }
}
</style>
