"""T-A-01~08：ignore / langdetect / loc / stackdetect / 大文件保护 / 扫描管线。"""

import os

import pytest

from app.core.filescanner import scan_project
from app.core.langdetect import detect_language
from app.core.loc import count_loc
from app.core.stackdetect import detect_stack


def write(path: str, content: str = "x\n") -> str:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    return path


def make_tree(tmp_path, structure: dict):
    for rel, content in structure.items():
        write(str(tmp_path / rel), content)


@pytest.fixture
def project(tmp_path):
    make_tree(
        tmp_path,
        {
            "src/a.js": "const a = 1;\n",
            "src/lib/util.py": "def f():\n    pass\n",
            "README.md": "# demo\n",
        },
    )
    return tmp_path


# ---- T-A-01 默认忽略规则 ----
def test_default_ignore_dirs_pruned(project):
    for rel in (
        "node_modules/x.js",
        ".git/config",
        "dist/b.js",
        "target/c.java",
        "build/d.o",
        "__pycache__/e.pyc",
        "venv/bin/f",
    ):
        write(str(project / rel))
    result = scan_project(project)
    paths = {f.path for f in result.files}
    assert not any(
        "node_modules" in p
        or ".git" in p
        or "dist" in p
        or "target" in p
        or "build" in p
        or "__pycache__" in p
        or "venv" in p
        for p in paths
    )
    assert result.ignored_count >= 7
    assert result.file_count == 3


def test_lock_and_minified_files_ignored(project):
    for rel in ("package-lock.json", "yarn.lock", "app.min.js", "bundle.js.map"):
        write(str(project / rel))
    result = scan_project(project)
    paths = {f.path for f in result.files}
    ignored = ("package-lock.json", "yarn.lock", "app.min.js", "bundle.js.map")
    assert not any(p in paths for p in ignored)
    assert result.ignored_count >= 4


# ---- T-A-02 .evocodeignore（含 ! 取反）----
def test_evocodeignore_with_negation(project):
    write(str(project / ".evocodeignore"), "generated/\n!generated/keep.js\n")
    write(str(project / "generated/a.js"), "x\n")
    write(str(project / "generated/keep.js"), "y\n")
    result = scan_project(project)
    paths = {f.path for f in result.files}
    assert "generated/keep.js" in paths, "! 取反应恢复文件"
    assert "generated/a.js" not in paths


# ---- T-A-03 后缀映射（20 种 + 未知归 OTHER）----
@pytest.mark.parametrize(
    "suffix,expected",
    [
        (".java", "Java"),
        (".py", "Python"),
        (".ts", "TypeScript"),
        (".tsx", "TypeScript"),
        (".js", "JavaScript"),
        (".jsx", "JavaScript"),
        (".go", "Go"),
        (".vue", "Vue"),
        (".html", "HTML"),
        (".htm", "HTML"),
        (".css", "CSS"),
        (".scss", "CSS"),
        (".sql", "SQL"),
        (".sh", "Shell"),
        (".md", "Markdown"),
        (".json", "JSON"),
        (".xml", "XML"),
        (".yml", "YAML"),
        (".yaml", "YAML"),
        (".kt", "Kotlin"),
        (".c", "C"),
        (".cpp", "C++"),
        (".cs", "C#"),
        (".rb", "Ruby"),
        (".php", "PHP"),
        (".rs", "Rust"),
        (".swift", "Swift"),
        (".scala", "Scala"),
        (".dart", "Dart"),
        (".lua", "Lua"),
        (".r", "R"),
        (".groovy", "Groovy"),
        (".bat", "Shell"),
        (".ps1", "PowerShell"),
        (".gradle", "Groovy"),
        (".toml", "TOML"),
        (".ini", "INI"),
    ],
)
def test_extension_mapping(suffix, expected):
    assert detect_language(f"src/App{suffix}") == expected


def test_unknown_extension_is_other():
    assert detect_language("src/blob.xyzabc") == "OTHER"


# ---- T-A-04 Dockerfile 文件名匹配 ----
def test_dockerfile_filename_detected():
    assert detect_language("Dockerfile") == "Dockerfile"
    assert detect_language("src/docker/Dockerfile") == "Dockerfile"


# ---- T-A-05 LOC：空行/注释不计 ----
def test_loc_counts_real_code_lines_only():
    text = (
        "package demo;\n"
        "\n"
        "// 单行注释\n"
        "/* 块注释\n"
        " * 第二行\n"
        " */\n"
        "public class A {\n"
        "    // 注释\n"
        "    int x = 1;\n"
        "}\n"
        "# python 注释\n"
        "def f():\n"
        "    pass\n"
        "\n"
    )
    assert count_loc(text) == 6  # package/class/int x/}/def/pass
    assert count_loc("  \n\t\n") == 0
    assert count_loc("#!/usr/bin/env python\nprint(1)\n") == 1


# ---- T-A-06 大文件保护（>2MB 跳过并计数）----
def test_big_file_skipped_and_counted(project):
    write(str(project / "src/huge.js"), "a" * (2 * 1024 * 1024 + 1))
    result = scan_project(project)
    assert not any(f.path == "src/huge.js" for f in result.files)
    assert result.skipped_big_files >= 1


def test_binary_file_skipped(project):
    path = write(str(project / "src/data.bin"), "")
    with open(path, "wb") as f:
        f.write(b"\x00\x01\x02binary")
    result = scan_project(project)
    assert not any(f.path == "src/data.bin" for f in result.files)
    assert result.ignored_count >= 1


# ---- T-A-07 pom.xml 技术栈 ----
def test_pom_xml_detects_spring_boot(tmp_path):
    write(
        str(tmp_path / "pom.xml"),
        """
<project>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.5.14</version>
  </parent>
</project>
""",
    )
    write(str(tmp_path / "src/main/java/com/x/App.java"), "class App {}\n")
    stack = detect_stack(tmp_path)
    assert "Spring Boot" in stack.frameworks
    assert "Maven" in stack.frameworks
    assert stack.has_backend is True


# ---- T-A-08 package.json 双框架 ----
def test_package_json_detects_vue_electron(tmp_path):
    write(str(tmp_path / "package.json"), json_deps(["vue", "electron"]))
    stack = detect_stack(tmp_path)
    assert "Vue" in stack.frameworks
    assert "Electron" in stack.frameworks
    assert stack.has_frontend is True


def json_deps(names):
    deps = ", ".join(f'"{n}": "1.0.0"' for n in names)
    return '{\n  "dependencies": { ' + deps + ' },\n  "devDependencies": {}\n}\n'


# ---- 语言占比与档案聚合 ----
def test_scan_result_aggregates_language_ratio(tmp_path):
    make_tree(
        tmp_path,
        {
            "a.py": "x = 1\n",
            "b.js": "const b = 2;\nconst c = 3;\n",
        },
    )
    result = scan_project(tmp_path)
    assert result.loc_total == 3
    assert result.file_count == 2
    assert result.languages["Python"] == pytest.approx(100 / 3, abs=0.1)
    assert result.languages["JavaScript"] == pytest.approx(200 / 3, abs=0.1)
