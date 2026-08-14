/**
 * 逐行分析标注逻辑测试：issuesByLine / renderLines / lineIssueClass 纯函数。
 * 从 index.vue 的 setup 导出无法直接单测，这里验证核心分组逻辑的可复用实现。
 */

import { describe, expect, it } from 'vitest'

interface Item {
  filePath: string | null
  line: number | null
  severity: string
  message: string
  aiSuggestion: string | null
}

/** 与 index.vue issuesByLine 相同的逻辑（行号分组）。 */
function issuesByLine(path: string, items: Item[]): Map<number, Item[]> {
  const map = new Map<number, Item[]>()
  for (const it of items) {
    if (it.filePath !== path || it.line == null) continue
    const arr = map.get(it.line) ?? []
    arr.push(it)
    map.set(it.line, arr)
  }
  return map
}

/** 与 index.vue renderLines 相同（content 拆行 + 行号）。 */
function renderLines(path: string | undefined, contentText: string, items: Item[]) {
  if (!path) return []
  const byLine = issuesByLine(path, items)
  return contentText.split('\n').map((text, idx) => ({
    no: idx + 1,
    text,
    issues: byLine.get(idx + 1) ?? [],
  }))
}

function lineIssueClass(issues: Item[] | undefined): string {
  if (!issues?.length) return ''
  const hasHigh = issues.some((i) => i.severity === 'BLOCKER' || i.severity === 'CRITICAL')
  if (hasHigh) return 'line-issue--error'
  const hasMajor = issues.some((i) => i.severity === 'MAJOR')
  if (hasMajor) return 'line-issue--warning'
  return 'line-issue--info'
}

const items: Item[] = [
  {
    filePath: 'a.py',
    line: 2,
    severity: 'CRITICAL',
    message: '硬编码密钥',
    aiSuggestion: '移入环境变量',
  },
  {
    filePath: 'a.py',
    line: 2,
    severity: 'MAJOR',
    message: '危险调用',
    aiSuggestion: '用 subprocess',
  },
  { filePath: 'a.py', line: 5, severity: 'MINOR', message: '魔法数字', aiSuggestion: '提取常量' },
  { filePath: 'b.py', line: 1, severity: 'MAJOR', message: '空 catch', aiSuggestion: null },
]

describe('逐行分析标注', () => {
  it('按 filePath+line 精确分组', () => {
    const byLine = issuesByLine('a.py', items)
    expect(byLine.get(2)?.length).toBe(2) // 同一行多问题
    expect(byLine.get(5)?.length).toBe(1)
    expect(byLine.get(1)).toBeUndefined() // b.py 的问题不混入
  })

  it('renderLines 生成带行号与问题标注的行', () => {
    const lines = renderLines('a.py', 'x = 1\ny = 2\nz = 3\n', items)
    expect(lines).toHaveLength(4) // 尾部换行产生末行空串
    expect(lines[0].no).toBe(1)
    expect(lines[0].issues).toHaveLength(0)
    expect(lines[1].no).toBe(2)
    expect(lines[1].issues).toHaveLength(2)
    expect(lines[2].issues).toHaveLength(0)
    expect(lines[3].no).toBe(4)
  })

  it('severity 映射 CSS 类（最高级优先）', () => {
    expect(lineIssueClass([{ severity: 'CRITICAL' } as Item])).toBe('line-issue--error')
    expect(lineIssueClass([{ severity: 'MAJOR' } as Item])).toBe('line-issue--warning')
    expect(lineIssueClass([{ severity: 'MINOR' } as Item])).toBe('line-issue--info')
    expect(lineIssueClass([])).toBe('')
    // 同行 CRITICAL+MAJOR → error
    expect(lineIssueClass([{ severity: 'MAJOR' } as Item, { severity: 'CRITICAL' } as Item])).toBe(
      'line-issue--error',
    )
  })

  it('path 为空返回空数组', () => {
    expect(renderLines(undefined, 'x', items)).toHaveLength(0)
  })
})
