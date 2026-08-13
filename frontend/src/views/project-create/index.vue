<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NForm,
  NFormItem,
  NInput,
  NRadioButton,
  NRadioGroup,
  NSelect,
  NSpace,
  NUpload,
  type UploadFileInfo,
} from 'naive-ui'
import { createFromGit, createFromZip } from '../../api/project'

const router = useRouter()

const mode = ref<'zip' | 'git'>('zip')
const name = ref('')
const description = ref('')
const fileList = ref<UploadFileInfo[]>([])
const repoUrl = ref('')
const cloneDepth = ref(1)
const loading = ref(false)
const error = ref('')

const MAX_ZIP_MB = 200

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
    const f = fileList.value[0]?.file
    if (!f) {
      error.value = '请选择 .zip 文件'
      return
    }
    if (!f.name.toLowerCase().endsWith('.zip')) {
      error.value = '仅支持 .zip 格式'
      return
    }
    if (f.size > MAX_ZIP_MB * 1024 * 1024) {
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
        fileList.value[0].file as File,
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
    router.push(`/projects/${id}`)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    loading.value = false
  }
}
</script>

<template>
  <div class="create-page">
    <NButton quaternary size="small" @click="router.push('/projects')">← 返回项目列表</NButton>
    <h1 class="page-title">新建项目</h1>
    <p class="page-desc">
      上传 zip 压缩包或填写 GitHub 仓库地址，创建后自动发起快扫（3 分钟内生成档案）。
    </p>

    <NRadioGroup v-model:value="mode" class="mode-switch">
      <NRadioButton value="zip" label="zip 上传" />
      <NRadioButton value="git" label="GitHub 仓库" />
    </NRadioGroup>

    <NForm class="create-form" label-placement="top">
      <NFormItem label="项目名">
        <NInput v-model:value="name" maxlength="100" placeholder="例如：chatez" clearable />
      </NFormItem>

      <NFormItem label="描述">
        <NInput
          v-model:value="description"
          type="textarea"
          :rows="2"
          placeholder="可选，一句话说明项目用途"
        />
      </NFormItem>

      <template v-if="mode === 'zip'">
        <NFormItem label="zip 文件">
          <NUpload v-model:file-list="fileList" accept=".zip" :max="1" :show-file-list="true">
            <NButton>选择文件</NButton>
          </NUpload>
          <p class="hint">
            ≤{{ MAX_ZIP_MB }}MB，解压后 ≤500MB；自动忽略 .git / node_modules 等目录
          </p>
        </NFormItem>
      </template>

      <template v-else>
        <NFormItem label="仓库地址">
          <NInput v-model:value="repoUrl" placeholder="https://github.com/owner/repo" clearable />
        </NFormItem>
        <NFormItem label="克隆深度">
          <NSelect
            :value="cloneDepth"
            :options="[
              { label: '浅克隆（depth=1，默认）', value: 1 },
              { label: '全量克隆（供演化分析）', value: 0 },
            ]"
            @update:value="(v) => (cloneDepth = v ?? 1)"
          />
          <p class="hint">全量克隆包含完整 commit 历史，耗时更长</p>
        </NFormItem>
      </template>

      <div v-if="error" class="alert-fail">{{ error }}</div>

      <NSpace>
        <NButton type="primary" :loading="loading" @click="submit">
          {{ loading ? '创建中…' : '创建并扫描' }}
        </NButton>
        <NButton :disabled="loading" @click="router.push('/projects')">取消</NButton>
      </NSpace>
    </NForm>
  </div>
</template>

<style scoped>
.create-page {
  max-width: 560px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 24px 28px;
}
.page-title {
  margin: 8px 0 4px;
  font-size: 20px;
  font-weight: 700;
}
.page-desc {
  margin: 0 0 20px;
  color: #8798ab;
  font-size: 13px;
}
.mode-switch {
  margin-bottom: 20px;
}
.create-form {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.hint {
  font-size: 12px;
  color: #8798ab;
  margin-top: 6px;
}
.alert-fail {
  padding: 10px 14px;
  border-radius: 4px;
  background: rgba(220, 38, 38, 0.08);
  color: #dc2626;
  font-size: 13px;
}
</style>
