"""T-A-L-07~10：SPI-1 解析器注册表 + languages 过滤。"""

from app.core.arch.archscan import _build_default_registry, architecture_scan


def test_registry_supports_five_languages():
    reg = _build_default_registry()
    exts = reg.supported_extensions()
    assert {".py", ".java", ".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".go"} <= exts
    assert reg.by_extension(".py") is not None
    assert reg.by_extension(".Go") is not None  # 后缀忽略大小写
    assert reg.by_extension(".rb") is None


def test_registry_select_languages_filters():
    reg = _build_default_registry()
    assert len(reg.select_languages(None)) == 5  # 空 → 全部
    assert {p.language for p in reg.select_languages([])} == {
        "python",
        "java",
        "javascript",
        "typescript",
        "go",
    }
    assert {p.language for p in reg.select_languages(["python"])} == {"python"}
    # 忽略大小写 + 多语言
    assert {p.language for p in reg.select_languages(["PYTHON", "Go"])} == {
        "python",
        "go",
    }
    # 未知语言 → 空集（调用方产出空结果，而非忽略参数）
    assert reg.select_languages(["ruby"]) == set()


def test_architecture_scan_languages_filter(tmp_path):
    (tmp_path / "svc.py").write_text("class PyService:\n    pass\n", encoding="utf-8")
    (tmp_path / "svc.java").write_text("class JavaService {}\n", encoding="utf-8")
    (tmp_path / "svc.go").write_text(
        "package main\ntype GoService struct{}\nfunc (s *GoService) Get() {}\n",
        encoding="utf-8",
    )

    all_keys = {n["nodeKey"] for n in architecture_scan(str(tmp_path))["nodes"]}
    assert {"PyService", "JavaService", "GoService"} <= all_keys

    # 仅 python
    py_keys = {
        n["nodeKey"] for n in architecture_scan(str(tmp_path), ["python"])["nodes"]
    }
    assert py_keys == {"PyService"}

    # 忽略大小写
    go_keys = {n["nodeKey"] for n in architecture_scan(str(tmp_path), ["GO"])["nodes"]}
    assert go_keys == {"GoService"}

    # 未知语言 → 空
    assert architecture_scan(str(tmp_path), ["ruby"])["nodes"] == []
