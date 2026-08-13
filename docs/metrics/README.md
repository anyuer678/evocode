# 质量指标台账（docs/metrics/）

> 采集手册与指标定义见《docs/11-技术债监控与质量指标体系.md》；本文档存放每版本快照。
> 节奏：每次发版/里程碑跑全量门禁 → 写 `YYYY-MM-DD-vX.Y.md` → 更新趋势（见 §3.1/3.2）。

## 快照列表

| 快照 | 日期 | 版本 | backend | analyzer | frontend |
|---|---|---|---|---|---|
| [2026-08-13-v1.2.md](2026-08-13-v1.2.md) | 2026-08-13 | v1.2（技术债清零 + A1/A2 达成） | 155/155 | 158/158 + ruff | lint+build ✅ / vitest 12/12 |
| [2026-08-13-v1.1.md](2026-08-13-v1.1.md) | 2026-08-13 | v1.1（P9 收官） | 149/149 | 141/141 + ruff | lint+build ✅ / vitest 12/12 |

## 采集命令（速查）

```powershell
# backend 单测（mvnw 在 PowerShell 下退出码误判，以 Tests run 行核对）
cmd /c "cd /d backend && mvnw.cmd test -o" | Select-String "Tests run:"

# analyzer 单测 + ruff
analyzer\.venv\Scripts\python.exe -m pytest analyzer/tests
analyzer\.venv\Scripts\python.exe -m ruff check analyzer

# frontend lint + 单测 + build
npm --prefix frontend run lint
npm --prefix frontend run test
npm --prefix frontend run build

# 全链路冒烟（三端健康检查）
scripts\smoke.ps1
```
