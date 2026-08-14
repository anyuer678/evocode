<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NAlert, NCard, NDataTable, NEmpty, NSpin, NStatistic, NTag } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { fetchDependencies } from '../../api/dependency'
import type { DependencyItem, DependencyResult } from '../../types/api'

const props = defineProps<{ projectId: number }>()

const loading = ref(true)
const errorMsg = ref('')
const data = ref<DependencyResult | null>(null)

const RISK_META: Record<
  string,
  { label: string; type: 'error' | 'warning' | 'success' | 'default' }
> = {
  HIGH: { label: '高危', type: 'error' },
  MEDIUM: { label: '中危', type: 'warning' },
  LOW: { label: '低', type: 'success' },
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

const columns = computed<DataTableColumns<DependencyItem>>(() => [
  {
    title: '组件',
    key: 'name',
    minWidth: 180,
    render: (r) => h('span', { class: 'dep-name' }, r.name),
  },
  { title: '版本', key: 'version', width: 110, render: (r) => r.version ?? '-' },
  {
    title: '类型',
    key: 'type',
    width: 80,
    render: (r) => h(NTag, { size: 'small', bordered: false }, () => TYPE_LABEL[r.type] ?? r.type),
  },
  {
    title: '风险',
    key: 'risk',
    width: 90,
    render: (r) => {
      if (!r.risk) return h(NTag, { size: 'small', bordered: false }, () => '未知')
      const meta = RISK_META[r.risk] ?? { label: r.risk, type: 'default' as const }
      return h(NTag, { size: 'small', bordered: false, type: meta.type }, () => meta.label)
    },
  },
  { title: '说明', key: 'reason', minWidth: 200, render: (r) => r.reason ?? '-' },
  { title: '建议版本', key: 'latest', width: 110, render: (r) => r.latest ?? '-' },
  {
    title: '建议',
    key: 'suggestion',
    minWidth: 180,
    render: (r) => (r.suggestion ? h('span', { class: 'dep-suggestion' }, r.suggestion) : '-'),
  },
])

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
  <div class="dependency">
    <NSpin :show="loading">
      <NAlert v-if="errorMsg" type="error" :show-icon="true" class="dep-alert">{{
        errorMsg
      }}</NAlert>
      <NEmpty
        v-else-if="!data || !data.available"
        description="未检测到 Maven/npm 依赖（项目缺少 pom.xml / package.json，发起完整分析后重试）"
        class="dep-empty"
      />
      <template v-else>
        <div class="dep-stats">
          <NCard size="small" class="dep-stat">
            <NStatistic label="总依赖" :value="stats.total" />
          </NCard>
          <NCard size="small" class="dep-stat">
            <NStatistic label="EOL 依赖" :value="stats.eol" />
          </NCard>
          <NCard size="small" class="dep-stat">
            <NStatistic label="高风险" :value="stats.high" />
          </NCard>
        </div>
        <NDataTable
          :columns="columns"
          :data="data?.dependencies ?? []"
          :row-key="(r: DependencyItem) => r.name"
          :bordered="false"
          :single-line="false"
          size="small"
          class="dep-table"
        />
      </template>
    </NSpin>
  </div>
</template>

<style scoped>
.dependency {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.dep-alert {
  margin-bottom: 4px;
}
.dep-empty {
  padding: 40px 0;
}
.dep-stats {
  display: flex;
  gap: 12px;
}
.dep-stat {
  flex: 1;
  max-width: 200px;
}
.dep-name {
  font-weight: 600;
  word-break: break-all;
}
.dep-suggestion {
  color: #1668dc;
  font-size: 12.5px;
}
</style>
