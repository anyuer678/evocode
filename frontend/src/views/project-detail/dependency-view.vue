<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchDependencies } from '../../api/dependency'
import type { DependencyItem, DependencyResult } from '../../types/api'

const props = defineProps<{ projectId: number }>()

const loading = ref(true)
const errorMsg = ref('')
const data = ref<DependencyResult | null>(null)

const RISK_LABEL: Record<string, string> = {
  HIGH: '高危',
  MEDIUM: '中危',
  LOW: '低',
}
const RISK_CLS: Record<string, string> = {
  HIGH: 'risk-high',
  MEDIUM: 'risk-medium',
  LOW: 'risk-low',
}
const TYPE_LABEL: Record<string, string> = {
  MAVEN: 'Maven',
  NPM: 'npm',
  PIP: 'pip',
  GO: 'go',
}

const stats = computed(() => {
  const deps = data.value?.dependencies ?? []
  return {
    total: deps.length,
    eol: deps.filter((d) => d.isEol).length,
    high: deps.filter((d) => d.risk === 'HIGH').length,
  }
})

const grouped = computed(() => {
  const deps = data.value?.dependencies ?? []
  const groups: Record<string, DependencyItem[]> = {
    HIGH: [],
    MEDIUM: [],
    UNKNOWN: [],
    OK: [],
  }
  for (const d of deps) {
    if (d.risk === 'HIGH' || d.risk === 'MEDIUM') {
      groups[d.risk].push(d)
    } else if (d.risk == null) {
      groups.UNKNOWN.push(d)
    } else {
      groups.OK.push(d)
    }
  }
  return [
    { key: 'HIGH', label: '高风险', cls: 'g-high', items: groups.HIGH },
    { key: 'MEDIUM', label: '中风险', cls: 'g-medium', items: groups.MEDIUM },
    { key: 'UNKNOWN', label: '未知版本', cls: 'g-unknown', items: groups.UNKNOWN },
    { key: 'OK', label: '正常', cls: 'g-ok', items: groups.OK },
  ].filter((g) => g.items.length > 0)
})

async function load(): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  try {
    data.value = await fetchDependencies(props.projectId)
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="dependency">
    <h2>依赖分析</h2>

    <p v-if="loading" class="muted">依赖分析加载中…</p>
    <p v-else-if="errorMsg" class="err">{{ errorMsg }}</p>
    <p v-else-if="!data || !data.available" class="muted">
      未检测到 Maven/npm 依赖（项目缺少 pom.xml / package.json，发起完整分析后重试）
    </p>

    <template v-else>
      <!-- 统计卡 -->
      <div class="dep-stats">
        <div class="stat-card">
          <span class="stat-num">{{ stats.total }}</span
          >总依赖
        </div>
        <div class="stat-card warn">
          <span class="stat-num">{{ stats.eol }}</span
          >EOL 依赖
        </div>
        <div class="stat-card danger">
          <span class="stat-num">{{ stats.high }}</span
          >高风险
        </div>
      </div>

      <!-- 风险分组 -->
      <div v-for="g in grouped" :key="g.key" class="dep-group" :class="g.cls">
        <h3>{{ g.label }}（{{ g.items.length }}）</h3>
        <table class="table dep-table">
          <thead>
            <tr>
              <th>组件</th>
              <th>版本</th>
              <th>类型</th>
              <th>风险</th>
              <th>说明</th>
              <th>建议版本</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in g.items" :key="d.name">
              <td class="dep-name">{{ d.name }}</td>
              <td class="dep-version">{{ d.version ?? '-' }}</td>
              <td>{{ TYPE_LABEL[d.type] ?? d.type }}</td>
              <td>
                <span v-if="d.risk" class="badge" :class="RISK_CLS[d.risk]">
                  {{ RISK_LABEL[d.risk] }}
                </span>
                <span v-else class="badge risk-unknown">未知</span>
              </td>
              <td class="dep-reason">{{ d.reason ?? '-' }}</td>
              <td>{{ d.latest ?? '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.dependency {
  margin-top: 24px;
}
.dep-stats {
  display: flex;
  gap: 12px;
  margin: 12px 0;
}
.stat-card {
  padding: 10px 18px;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-sm, 6px);
  font-size: 13px;
  color: var(--text-muted, #64748b);
}
.stat-num {
  display: block;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary, #1e293b);
}
.stat-card.warn .stat-num {
  color: #b26a00;
}
.stat-card.danger .stat-num {
  color: #d32f2f;
}
.dep-group {
  margin-bottom: 16px;
}
.dep-group h3 {
  font-size: 14px;
  margin: 10px 0 6px;
}
.dep-table {
  font-size: 13px;
}
.dep-name {
  font-weight: 600;
  max-width: 320px;
  word-break: break-all;
}
.dep-version {
  font-variant-numeric: tabular-nums;
}
.dep-reason {
  color: var(--text-muted, #64748b);
  max-width: 360px;
}
.badge {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}
.risk-high {
  background: #fdecea;
  color: #d32f2f;
}
.risk-medium {
  background: #fff3e0;
  color: #b26a00;
}
.risk-low {
  background: #e8f5e9;
  color: #2e7d32;
}
.risk-unknown {
  background: var(--bg-muted, #eef2f7);
  color: var(--text-muted, #64748b);
}
.err {
  color: #d32f2f;
  font-size: 13px;
}
</style>
