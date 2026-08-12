<template>
  <section class="debt">
    <div class="debt__head">
      <h3>技术债</h3>
      <div class="debt__head-right">
        <button type="button" class="debt__btn" @click="openCreate">＋ 手动登记</button>
        <div class="debt__filters">
          <button
            v-for="s in statusOptions"
            :key="s.value"
            type="button"
            class="debt__filter"
            :class="{ 'debt__filter--active': statusFilter === s.value }"
            @click="onFilter(s.value)"
          >
            {{ s.label }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="loading" class="debt__state">加载中…</div>
    <div v-else-if="!debts.length" class="debt__state">暂无技术债，完成一次分析后自动生成</div>
    <ul v-else class="debt__list">
      <li v-for="d in debts" :key="d.id" class="debt__item">
        <div class="debt__item-head">
          <span class="debt__badge" :class="'debt__badge--' + d.level.toLowerCase()">{{
            d.level
          }}</span>
          <span class="debt__source">{{ sourceLabel(d.source) }}</span>
          <span class="debt__title">{{ d.title }}</span>
          <span class="debt__status" :class="'debt__status--' + d.status.toLowerCase()">{{
            statusLabel(d.status)
          }}</span>
          <button v-if="canAct(d.status)" class="debt__act" type="button" @click="openAction(d)">
            处理
          </button>
        </div>
        <p v-if="d.description" class="debt__desc">{{ d.description }}</p>
        <p v-if="d.suggestion" class="debt__sugg">建议：{{ d.suggestion }}</p>
        <p v-if="d.status === 'DONE' && d.resolvedAt" class="debt__meta">
          已解决 {{ formatTime(d.resolvedAt) }}
        </p>
        <p v-if="d.status === 'WONTFIX'" class="debt__meta">不修复</p>
      </li>
    </ul>

    <!-- 手动登记弹层（TD-04） -->
    <Teleport to="body">
      <div v-if="creating" class="debt__modal" @click.self="creating = false">
        <div class="debt__modal-box">
          <h4>手动登记技术债</h4>
          <input v-model="createForm.title" class="debt__input" placeholder="标题（必填）" />
          <select v-model="createForm.level" class="debt__input">
            <option value="HIGH">HIGH</option>
            <option value="MEDIUM">MEDIUM</option>
            <option value="LOW">LOW</option>
          </select>
          <textarea
            v-model="createForm.description"
            class="debt__input debt__note"
            rows="2"
            placeholder="描述（可选）"
          />
          <textarea
            v-model="createForm.suggestion"
            class="debt__input debt__note"
            rows="2"
            placeholder="建议（可选）"
          />
          <div class="debt__modal-actions">
            <button type="button" class="debt__btn" @click="creating = false">取消</button>
            <button
              type="button"
              class="debt__btn debt__btn--ok"
              :disabled="submitting"
              @click="submitCreate"
            >
              {{ submitting ? '提交中…' : '登记' }}
            </button>
          </div>
          <p v-if="createError" class="debt__error">{{ createError }}</p>
        </div>
      </div>
    </Teleport>

    <!-- 状态操作弹层 -->
    <Teleport to="body">
      <div v-if="action" class="debt__modal" @click.self="action = null">
        <div class="debt__modal-box">
          <h4>{{ action.title }}</h4>
          <select v-model="actionTarget" class="debt__input">
            <option value="DOING">标记进行中</option>
            <option value="DONE">标记已解决（需填解决说明）</option>
            <option value="WONTFIX">标记不修复（需填原因）</option>
          </select>
          <textarea
            v-model="actionNote"
            class="debt__input debt__note"
            rows="3"
            :placeholder="
              actionTarget === 'DONE'
                ? '解决说明（必填）'
                : actionTarget === 'WONTFIX'
                  ? '不修复原因（必填）'
                  : '备注（可选）'
            "
          />
          <div class="debt__modal-actions">
            <button type="button" class="debt__btn" @click="action = null">取消</button>
            <button
              type="button"
              class="debt__btn debt__btn--ok"
              :disabled="submitting"
              @click="submitAction"
            >
              {{ submitting ? '提交中…' : '确认' }}
            </button>
          </div>
          <p v-if="actionError" class="debt__error">{{ actionError }}</p>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
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

const formatTime = (t: string): string => new Date(t).toLocaleString()

async function load() {
  loading.value = true
  try {
    const page = await fetchTechDebts(props.projectId, statusFilter.value || undefined, 1, 100)
    debts.value = page.items
  } catch (err) {
    console.error('加载技术债失败', err)
  } finally {
    loading.value = false
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

<style scoped>
.debt {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  padding: 16px;
  background: var(--bg-card, #fff);
}
.debt__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.debt__head h3 {
  margin: 0;
  font-size: 15px;
}
.debt__head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.debt__filters {
  display: flex;
  gap: 6px;
}
.debt__filter {
  border: 1px solid var(--border-color, #e5e7eb);
  background: var(--bg-card);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  color: var(--text-secondary, #6b7280);
}
.debt__filter--active {
  border-color: var(--ok-color, #16a34a);
  color: var(--ok-color, #16a34a);
  background: rgba(22, 163, 74, 0.08);
}
.debt__state {
  color: var(--text-secondary, #6b7280);
  font-size: 13px;
  padding: 12px 0;
}
.debt__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.debt__item {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
}
.debt__item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.debt__badge {
  font-size: 11px;
  font-weight: 600;
  border-radius: 4px;
  padding: 1px 6px;
}
.debt__badge--high {
  background: var(--fail-weak);
  color: var(--fail-color);
}
.debt__badge--medium {
  background: var(--warn-weak);
  color: var(--warn-color);
}
.debt__badge--low {
  background: var(--info-weak);
  color: var(--info-color);
}
.debt__source {
  font-size: 11px;
  color: var(--text-secondary, #6b7280);
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 4px;
  padding: 0 5px;
}
.debt__title {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
}
.debt__status {
  font-size: 11px;
  border-radius: 4px;
  padding: 1px 6px;
}
.debt__status--open {
  background: var(--bg-muted);
  color: var(--text-secondary);
}
.debt__status--doing {
  background: var(--info-weak);
  color: var(--info-color);
}
.debt__status--done {
  background: var(--ok-weak);
  color: var(--ok-color);
}
.debt__status--wonfix {
  background: var(--purple-weak);
  color: var(--purple-color);
}
.debt__act {
  border: none;
  background: var(--ok-color, #16a34a);
  color: var(--bg-card);
  border-radius: 4px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.debt__desc,
.debt__sugg {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  line-height: 1.6;
}
.debt__meta {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--text-secondary, #6b7280);
}
.debt__modal {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 90;
}
.debt__modal-box {
  background: var(--bg-card);
  border-radius: 10px;
  padding: 18px;
  width: 380px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.debt__modal-box h4 {
  margin: 0;
  font-size: 14px;
}
.debt__input {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  padding: 6px 8px;
  font-size: 13px;
  font-family: inherit;
}
.debt__note {
  resize: vertical;
}
.debt__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.debt__btn {
  border: 1px solid var(--border-color, #e5e7eb);
  background: var(--bg-card);
  border-radius: 6px;
  padding: 5px 14px;
  font-size: 13px;
  cursor: pointer;
}
.debt__btn--ok {
  background: var(--ok-color, #16a34a);
  border-color: var(--ok-color, #16a34a);
  color: var(--bg-card);
}
.debt__error {
  color: var(--fail-color, #dc2626);
  font-size: 12px;
  margin: 0;
}
</style>
