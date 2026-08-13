<script setup lang="ts">
import { RouterView } from 'vue-router'
import {
  NConfigProvider,
  NDialogProvider,
  NLayout,
  NLayoutContent,
  NLayoutHeader,
  NMessageProvider,
} from 'naive-ui'
import type { GlobalThemeOverrides } from 'naive-ui'

const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#1668dc',
    primaryColorHover: '#2f7ce8',
    primaryColorPressed: '#1254b8',
    primaryColorSuppl: '#2f7ce8',
    borderRadius: '4px',
    fontSize: '13.5px',
  },
  Layout: {
    color: '#f2f6fb',
    headerColor: '#ffffff',
  },
  Card: {
    borderRadius: '6px',
  },
  DataTable: {
    borderRadius: '4px',
  },
}
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides">
    <n-message-provider>
      <n-dialog-provider>
        <n-layout position="absolute" class="app-layout">
          <n-layout-header bordered class="app-header">
            <div class="app-header-inner">
              <RouterLink to="/" class="app-logo">EvoCode</RouterLink>
              <nav class="app-nav">
                <RouterLink to="/dashboard" class="app-nav-link">健康总览</RouterLink>
                <RouterLink to="/projects" class="app-nav-link">项目档案</RouterLink>
              </nav>
              <span class="app-tagline">AI 软件体检与演化平台</span>
            </div>
          </n-layout-header>
          <n-layout-content class="app-content">
            <RouterView v-slot="{ Component }">
              <Transition name="fade" mode="out-in">
                <component :is="Component" />
              </Transition>
            </RouterView>
          </n-layout-content>
        </n-layout>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>

<style>
.app-layout {
  min-height: 100vh;
}
.app-header {
  position: sticky;
  top: 0;
  z-index: 50;
  height: 48px;
  display: flex;
  align-items: center;
  padding: 0 20px;
}
.app-header-inner {
  display: flex;
  align-items: center;
  gap: 20px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
}
.app-logo {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary, #1b2633);
  text-decoration: none;
  letter-spacing: 0.03em;
  white-space: nowrap;
}
.app-logo:hover {
  color: var(--primary-color, #1668dc);
}
.app-nav {
  display: flex;
  gap: 2px;
}
.app-nav-link {
  padding: 6px 12px;
  border-radius: 4px;
  font-size: 13.5px;
  color: #55667a;
  text-decoration: none;
  transition:
    color 150ms ease,
    background 150ms ease;
}
.app-nav-link:hover {
  color: #1b2633;
  background: #f6f9fd;
}
.app-nav-link.router-link-active {
  color: #1668dc;
  font-weight: 600;
}
.app-tagline {
  margin-left: auto;
  font-size: 12px;
  color: #8798ab;
  white-space: nowrap;
}
.app-content {
  padding: 16px 20px 48px;
  max-width: 1400px;
  margin: 0 auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 150ms ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 720px) {
  .app-header {
    padding: 0 12px;
  }
  .app-tagline {
    display: none;
  }
  .app-content {
    padding: 10px 10px 40px;
  }
}
</style>
