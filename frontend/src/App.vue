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
    borderRadius: '6px',
    fontSize: '14px',
    fontFamily:
      "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    textColorBase: '#1b2633',
    textColor1: '#1b2633',
    textColor2: '#55667a',
    textColor3: '#8798ab',
    bodyColor: '#f2f6fb',
    cardColor: '#ffffff',
    borderColor: '#e2e8f0',
    dividerColor: '#eef1f5',
    hoverColor: '#f6f9fd',
  },
  Layout: {
    color: '#f2f6fb',
    headerColor: '#ffffff',
    siderColor: '#ffffff',
  },
  Card: {
    borderRadius: '8px',
  },
  DataTable: {
    borderRadius: '6px',
    thColor: '#f8fafc',
  },
  Button: {
    borderRadiusMedium: '6px',
    borderRadiusSmall: '6px',
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
              <RouterLink to="/" class="app-logo">
                <span class="app-logo-mark" aria-hidden="true">E</span>
                <span class="app-logo-name">EvoCode</span>
              </RouterLink>
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
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 24px;
  box-shadow: 0 1px 0 var(--divider-color, #eef1f5);
}
.app-header-inner {
  display: flex;
  align-items: center;
  gap: 28px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
}
.app-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  white-space: nowrap;
}
.app-logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #1668dc, #4a8ef0);
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 0;
}
.app-logo-name {
  font-size: 17px;
  font-weight: 700;
  color: #1b2633;
  letter-spacing: 0.02em;
}
.app-logo:hover .app-logo-name {
  color: #1668dc;
}
.app-nav {
  display: flex;
  gap: 4px;
}
.app-nav-link {
  padding: 8px 14px;
  border-radius: 6px;
  font-size: 14px;
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
  background: rgba(22, 104, 220, 0.08);
}
.app-tagline {
  margin-left: auto;
  font-size: 12.5px;
  color: #8798ab;
  white-space: nowrap;
}
.app-content {
  padding: 24px 24px 56px;
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
