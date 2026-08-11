<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createFromGit, createFromZip } from '../../api/project'

const router = useRouter()

const mode = ref<'zip' | 'git'>('zip')

const name = ref('')
const description = ref('')
const file = ref<File | null>(null)
const repoUrl = ref('')
const cloneDepth = ref(1)
const loading = ref(false)
const error = ref('')

const MAX_ZIP_MB = 200

function pickFile(e: Event) {
  const el = e.target as HTMLInputElement
  file.value = el.files?.[0] ?? null
}

async function submit() {
  error.value = ''
  const n = name.value.trim()
  if (!n) {
    error.value = '请填写项目名'
    return
  }
  if (n.length > 100) {
    error.value = '项目名不能超过 100 字符'
    return
  }
  if (mode.value === 'zip') {
    if (!file.value) {
      error.value = '请选择 .zip 文件'
      return
    }
    if (!file.value.name.toLowerCase().endsWith('.zip')) {
      error.value = '仅支持 .zip 格式'
      return
    }
    if (file.value.size > MAX_ZIP_MB * 1024 * 1024) {
      error.value = `zip 不能超过 ${MAX_ZIP_MB}MB`
      return
    }
  } else {
    const url = repoUrl.value.trim()
    if (!url) {
      error.value = '请填写 GitHub 仓库地址'
      return
    }
    if (!/^https?:\/\/.+\/.+/.test(url)) {
      error.value = '仓库地址格式非法（应为 https://github.com/xxx/repo）'
      return
    }
  }

  loading.value = true
  try {
    let id: number
    if (mode.value === 'zip') {
      const created = await createFromZip(
        n,
        file.value as File,
        description.value.trim() || undefined,
      )
      id = created.id
    } else {
      const created = await createFromGit(
        n,
        repoUrl.value.trim(),
        description.value.trim() || undefined,
        cloneDepth.value,
      )
      id = created.id
    }
    // 创建即触发异步快扫，直接进详情页观察档案生成
    router.push(`/projects/${id}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
}
</script>

<template>
  <section class="page">
    <a class="back" @click="router.push('/projects')">← 返回项目列表</a>
    <h1>新建项目</h1>
    <p class="desc">
      上传 zip 压缩包或填写 GitHub 仓库地址，创建后自动发起快扫（3 分钟内生成档案）。
    </p>

    <div class="tabs">
      <button class="tab" :class="{ active: mode === 'zip' }" type="button" @click="mode = 'zip'">
        zip 上传
      </button>
      <button class="tab" :class="{ active: mode === 'git' }" type="button" @click="mode = 'git'">
        GitHub 仓库
      </button>
    </div>

    <form class="form" @submit.prevent="submit">
      <label class="field">
        <span class="label">项目名 <em>*</em></span>
        <input v-model="name" class="input" maxlength="100" placeholder="例如：chatez" />
      </label>

      <label class="field">
        <span class="label">描述</span>
        <textarea
          v-model="description"
          class="input textarea"
          rows="2"
          placeholder="可选，一句话说明项目用途"
        />
      </label>

      <template v-if="mode === 'zip'">
        <label class="field">
          <span class="label">zip 文件 <em>*</em></span>
          <input class="input" type="file" accept=".zip" @change="pickFile" />
          <span class="hint"
            >≤{{ MAX_ZIP_MB }}MB，解压后 ≤500MB；自动忽略 .git / node_modules 等目录</span
          >
        </label>
      </template>

      <template v-else>
        <label class="field">
          <span class="label">仓库地址 <em>*</em></span>
          <input v-model="repoUrl" class="input" placeholder="https://github.com/owner/repo" />
        </label>
        <label class="field">
          <span class="label">克隆深度</span>
          <select v-model.number="cloneDepth" class="input select">
            <option :value="1">浅克隆（depth=1，默认）</option>
            <option :value="0">全量克隆（供演化分析）</option>
          </select>
          <span class="hint">全量克隆包含完整 commit 历史，耗时更长</span>
        </label>
      </template>

      <div v-if="error" class="alert-fail">{{ error }}</div>

      <div class="actions">
        <button class="btn-primary" type="submit" :disabled="loading">
          {{ loading ? '创建中…' : '创建并扫描' }}
        </button>
        <button class="btn" type="button" :disabled="loading" @click="router.push('/projects')">
          取消
        </button>
      </div>
    </form>
  </section>
</template>

<style scoped>
.page {
  background: var(--bg-page);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 24px 28px;
  max-width: 720px;
}
.back {
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
}
.back:hover {
  color: var(--primary-color);
}
.desc {
  margin: 4px 0 16px;
  color: var(--text-secondary);
  font-size: 13px;
}
.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--border-color);
}
.tab {
  padding: 8px 16px;
  border: none;
  background: none;
  font-size: 14px;
  color: var(--text-secondary);
  cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab.active {
  color: var(--primary-color);
  border-bottom-color: var(--primary-color);
  font-weight: 600;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.label {
  font-size: 13px;
  font-weight: 600;
}
.label em {
  color: var(--fail-color);
  font-style: normal;
}
.input {
  height: 36px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
}
.input:focus {
  outline: 2px solid rgba(47, 111, 237, 0.25);
  border-color: var(--primary-color);
}
.textarea {
  height: auto;
  padding: 8px 10px;
  resize: vertical;
  font-family: inherit;
}
.select {
  min-width: 200px;
}
.hint {
  font-size: 12px;
  color: var(--text-secondary);
}
.alert-fail {
  padding: 10px 14px;
  border-radius: 6px;
  background: rgba(220, 38, 38, 0.08);
  color: var(--fail-color);
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 10px;
  margin-top: 4px;
}
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 6px;
  background: var(--primary-color);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.btn-primary:hover {
  filter: brightness(1.08);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.btn {
  height: 36px;
  padding: 0 18px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  cursor: pointer;
}
.btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
}
</style>
