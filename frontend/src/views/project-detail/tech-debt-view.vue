<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NForm,
  NFormItem,
  NInput,
  NModal,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NSpin,
  NTag,
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { createTechDebt, fetchTechDebts, updateTechDebtStatus } from '../../api/debt'
import type { TechDebtItem, TechDebtStatus } from '../../types/api'

const props = defineProps<{ projectId: number }>()

const statusOptions: { value: TechDebtStatus | ''; label: string }[] = [
  { value: '', label: '全部' },
  { value: 'OPEN', label: '待处理' },
  { value: 'DOING', label: '进行中' },
  { value: 'DONE', label: '已解决' },
  { value: 'WONTFIX', label: '不修复' },
]

const debts = ref<TechDebtItem[]>([])
const statusFilter = ref<TechDebtStatus | ''>('')
const loading = ref(false)
const errorMsg = ref('')
const action = ref<TechDebtItem | null>(null)
const actionTarget = ref<TechDebtStatus>('DOING')
const actionNote = ref('')
const submitting = ref(false)
const actionError = ref('')

// ---- TD-04：手动登记 ----
const creating = ref(false)
const createError = ref('')
const createForm = ref({ title: '', level: 'MEDIUM', description: '', suggestion: '' })

function openCreate() {
  createForm.value = { title: '', level: 'MEDIUM', description: '', suggestion: '' }
  createError.value = ''
  creating.value = true
}

async function submitCreate() {
  const title = createForm.value.title.trim()
  if (!title) {
    createError.value = '标题必填'
    return
  }
  submitting.value = true
  try {
    await createTechDebt(props.projectId, {
      title,
      level: createForm.value.level,
      description: createForm.value.description.trim() || undefined,
      suggestion: createForm.value.suggestion.trim() || undefined,
    })
    creating.value = false
    await load()
  } catch (err) {
    createError.value = err instanceof Error ? err.message : '登记失败'
  } finally {
    submitting.value = false
  }
}

const LEVEL_TYPE: Record<string, 'error' | 'warning' | 'default'> = {
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'default',
}
const STATUS_TYPE: Record<TechDebtStatus, 'default' | 'info' | 'success' | 'warning'> = {
  OPEN: 'default',
  DOING: 'info',
  DONE: 'success',
  WONTFIX: 'warning',
}

const columns = computed<DataTableColumns<TechDebtItem>>(() => [
  {
    title: '级别',
    key: 'level',
    width: 80,
    render: (r) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: LEVEL_TYPE[r.level] ?? 'default' },
        () => r.level,
      ),
  },
  {
    title: '来源',
    key: 'source',
    width: 90,
    render: (r) => h(NTag, { size: 'small', bordered: false }, () => sourceLabel(r.source)),
  },
  { title: '标题', key: 'title', minWidth: 200, render: (r) => r.title },
  { title: '描述', key: 'description', minWidth: 160, render: (r) => r.description ?? '-' },
  { title: '建议', key: 'suggestion', minWidth: 160, render: (r) => r.suggestion ?? '-' },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (r) =>
      h(NTag, { size: 'small', bordered: false, type: STATUS_TYPE[r.status] }, () =>
        statusLabel(r.status),
      ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 80,
    render: (r) =>
      canAct(r.status)
        ? h(
            NButton,
            { size: 'small', quaternary: true, type: 'primary', onClick: () => openAction(r) },
            () => '处理',
          )
        : null,
  },
])
const sourceLabel = (s: TechDebtItem['source']): string =>
  ({
    ARCH: '架构',
    QUALITY: '质量',
    DEPEND: '依赖',
    EVOLUTION: '演化',
    AI_DOCTOR: 'AI 医生',
    MANUAL: '手动',
  })[s] ?? s

const statusLabel = (s: TechDebtStatus): string =>
  ({ OPEN: '待处理', DOING: '进行中', DONE: '已解决', WONTFIX: '不修复' })[s] ?? s

const canAct = (s: TechDebtStatus): boolean => s === 'OPEN' || s === 'DOING'

let loadSeq = 0

async function load() {
  const seq = ++loadSeq
  loading.value = true
  errorMsg.value = ''
  try {
    const page = await fetchTechDebts(props.projectId, statusFilter.value || undefined, 1, 100)
    if (seq !== loadSeq) return // 审查 L2：过期响应丢弃
    debts.value = page.items
  } catch (err) {
    if (seq !== loadSeq) return
    errorMsg.value = err instanceof Error ? err.message : String(err) // 审查 L1：失败态与空态分离
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function onFilter(v: TechDebtStatus | '') {
  statusFilter.value = v
  void load()
}

function openAction(d: TechDebtItem) {
  action.value = d
  actionTarget.value = d.status === 'DOING' ? 'DONE' : 'DOING'
  actionNote.value = ''
  actionError.value = ''
}

async function submitAction() {
  if (!action.value) return
  const target = actionTarget.value
  const note = actionNote.value.trim()
  // 审查修复：契约 §3.12 状态机仅允许 OPEN→DOING/DONE/WONTFIX、DOING→DONE；
  // DOING→WONTFIX 非法，前端前置拦截（此前只靠后端 2xxx 报错）
  if (action.value.status === 'DOING' && target === 'WONTFIX') {
    actionError.value = '进行中的技术债不允许标记为不修复（仅可 DONE）'
    return
  }
  if (target === 'DONE' && !note) {
    actionError.value = '解决说明必填'
    return
  }
  if (target === 'WONTFIX' && !note) {
    actionError.value = '不修复原因必填'
    return
  }
  submitting.value = true
  try {
    await updateTechDebtStatus(
      action.value.id,
      target,
      target === 'DONE' ? note : undefined,
      target === 'WONTFIX' ? note : undefined,
    )
    action.value = null
    await load()
  } catch (err) {
    actionError.value = err instanceof Error ? err.message : '更新失败'
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="debt">
    <NCard size="small" class="debt-card">
      <template #header>
        <div class="debt-head">
          <span class="debt-title">技术债</span>
          <NSpace size="small">
            <NButton size="small" type="primary" @click="openCreate">＋ 手动登记</NButton>
            <NRadioGroup v-model:value="statusFilter" size="small" @update:value="onFilter">
              <NRadioButton
                v-for="s in statusOptions"
                :key="s.value"
                :value="s.value"
                :label="s.label"
              />
            </NRadioGroup>
          </NSpace>
        </div>
      </template>

      <NSpin :show="loading">
        <NAlert v-if="errorMsg" type="error" :show-icon="true">加载失败：{{ errorMsg }}</NAlert>
        <NEmpty v-else-if="!debts.length" description="暂无技术债，完成一次分析后自动生成" />
        <NDataTable
          v-else
          :columns="columns"
          :data="debts"
          :row-key="(r) => r.id"
          :bordered="false"
          :single-line="false"
          size="small"
        />
      </NSpin>
    </NCard>

    <!-- 状态迁移 -->
    <NModal
      :show="action != null"
      preset="card"
      title="处理技术债"
      style="width: 480px"
      @update:show="
        (v: boolean) => {
          if (!v) action = null
        }
      "
    >
      <NForm label-placement="top">
        <NFormItem label="目标状态">
          <NSelect
            :value="actionTarget"
            :options="[
              { label: '进行中', value: 'DOING', disabled: action?.status === 'DOING' },
              { label: '已解决（需填说明）', value: 'DONE' },
              {
                label: '不修复（需填原因）',
                value: 'WONTFIX',
                disabled: action?.status === 'DOING',
              },
            ]"
            @update:value="(v) => (actionTarget = v as TechDebtStatus)"
          />
        </NFormItem>
        <NFormItem label="备注 / 原因">
          <NInput
            v-model:value="actionNote"
            type="textarea"
            :rows="3"
            :placeholder="
              actionTarget === 'DONE'
                ? '解决说明（必填）'
                : actionTarget === 'WONTFIX'
                  ? '不修复原因（必填）'
                  : '备注（可选）'
            "
          />
        </NFormItem>
        <NAlert v-if="actionError" type="error" :show-icon="true" class="debt-err">{{
          actionError
        }}</NAlert>
        <template #footer>
          <NSpace>
            <NButton type="primary" :loading="submitting" @click="submitAction">确认</NButton>
            <NButton @click="action = null">取消</NButton>
          </NSpace>
        </template>
      </NForm>
    </NModal>

    <!-- 手动登记 -->
    <NModal v-model:show="creating" preset="card" title="手动登记技术债" style="width: 480px">
      <NForm label-placement="top">
        <NFormItem label="标题">
          <NInput v-model:value="createForm.title" maxlength="100" placeholder="债务标题" />
        </NFormItem>
        <NFormItem label="级别">
          <NRadioGroup v-model:value="createForm.level">
            <NRadioButton value="HIGH" label="高" />
            <NRadioButton value="MEDIUM" label="中" />
            <NRadioButton value="LOW" label="低" />
          </NRadioGroup>
        </NFormItem>
        <NFormItem label="描述">
          <NInput
            v-model:value="createForm.description"
            type="textarea"
            :rows="2"
            placeholder="可选"
          />
        </NFormItem>
        <NFormItem label="建议">
          <NInput
            v-model:value="createForm.suggestion"
            type="textarea"
            :rows="2"
            placeholder="可选"
          />
        </NFormItem>
        <NAlert v-if="createError" type="error" :show-icon="true" class="debt-err">{{
          createError
        }}</NAlert>
        <template #footer>
          <NSpace>
            <NButton type="primary" :loading="submitting" @click="submitCreate">登记</NButton>
            <NButton @click="creating = false">取消</NButton>
          </NSpace>
        </template>
      </NForm>
    </NModal>
  </div>
</template>
<style scoped>
.debt {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.debt-card {
  background: #fff;
}
.debt-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.debt-title {
  font-size: 15px;
  font-weight: 600;
}
.debt-err {
  margin-bottom: 8px;
}
</style>
