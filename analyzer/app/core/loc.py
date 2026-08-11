"""LOC 统计（FR-2.3：空行 / 单行注释 / 块注释 / shebang 不计）。"""


def count_loc(text: str) -> int:
    count = 0
    in_block = False
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if in_block:
            if "*/" in line:
                in_block = False
                rest = line[line.index("*/") + 2 :].strip()
                if rest and not rest.startswith(("//", "#")):
                    count += 1
            continue
        if line.startswith("#!"):
            continue
        if line.startswith(("//", "#")):
            continue
        if line.startswith("/*"):
            if "*/" not in line:
                in_block = True
            continue
        count += 1
    return count
