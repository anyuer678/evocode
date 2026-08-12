"""T-D-01~06：P9d 依赖分析——pom/package 解析、EOL 判定、端点契约。"""

from pathlib import Path

from fastapi.testclient import TestClient

from app.core.dependency.dep_eol_rules import find_eol_rule, version_major
from app.core.dependency.depscan import scan_dependencies
from app.main import app

client = TestClient(app)


def _write(tmp_path: Path, name: str, content: str) -> Path:
    f = tmp_path / name
    f.write_text(content, encoding="utf-8")
    return f


# ---- 解析 ----

def test_pom_parses_direct_dependencies(tmp_path: Path):
    _write(tmp_path, "pom.xml", """
<project>
  <groupId>com.demo</groupId>
  <artifactId>app</artifactId>
  <version>1.0.0</version>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
      <version>2.5.14</version>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
    </dependency>
  </dependencies>
</project>
""")
    result = scan_dependencies(str(tmp_path))
    assert result["available"] is True
    names = [d["name"] for d in result["dependencies"]]
    assert "org.springframework.boot:spring-boot-starter-web" in names
    assert "org.projectlombok:lombok" in names
    web = next(d for d in result["dependencies"]
               if d["name"].endswith("spring-boot-starter-web"))
    assert web["version"] == "2.5.14"
    assert web["type"] == "MAVEN"
    assert web["file"] == "pom.xml"
    # 无 version 的依赖回退 project 版本
    lombok = next(d for d in result["dependencies"]
                  if d["name"].endswith("lombok"))
    assert lombok["version"] == "1.0.0"


def test_pom_skips_dependency_management(tmp_path: Path):
    # dependencyManagement 中的 <dependency> 块不应被当作直接依赖
    _write(tmp_path, "pom.xml", """
<project>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.netty</groupId>
        <artifactId>netty-all</artifactId>
        <version>4.1.0</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.demo</groupId>
      <artifactId>core</artifactId>
      <version>1.2.0</version>
    </dependency>
  </dependencies>
</project>
""")
    result = scan_dependencies(str(tmp_path))
    names = [d["name"] for d in result["dependencies"]]
    assert "io.netty:netty-all" not in names
    assert "com.demo:core" in names


def test_package_json_parses_both_sections(tmp_path: Path):
    _write(tmp_path, "package.json", """
{ "name": "demo", "version": "0.1.0",
  "dependencies": { "vue": "^2.6.14", "axios": "~1.7.0" },
  "devDependencies": { "vite": "5.4.0" } }
""")
    result = scan_dependencies(str(tmp_path))
    assert result["available"] is True
    deps = {d["name"]: d for d in result["dependencies"]}
    assert set(deps) == {"vue", "axios", "vite"}
    assert deps["vue"]["version"] == "2.6.14"  # ^ 已剥离
    assert deps["axios"]["version"] == "1.7.0"
    assert deps["vite"]["type"] == "NPM"
    assert deps["vite"]["file"] == "package.json"


def test_package_json_skips_git_url_version(tmp_path: Path):
    # git URL 依赖（git+https://…）版本不提取——URL 不是版本
    _write(tmp_path, "package.json", """
{ "name": "demo",
  "dependencies": {
    "my-lib": "git+https://github.com/me/my-lib.git#v1.2.3",
    "axios": "^1.7.0" } }
""")
    result = scan_dependencies(str(tmp_path))
    deps = {d["name"]: d for d in result["dependencies"]}
    assert deps["my-lib"]["version"] is None
    assert deps["axios"]["version"] == "1.7.0"


def test_pom_skips_parent_version(tmp_path: Path):
    # parent 块的 version 是父工程版本；剥离后无 version 的直接依赖
    # 回退 project 版本（1.2.0）
    _write(tmp_path, "pom.xml", """
<project>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.5.14</version>
  </parent>
  <groupId>com.demo</groupId>
  <artifactId>app</artifactId>
  <version>1.2.0</version>
  <dependencies>
    <dependency>
      <groupId>com.demo</groupId>
      <artifactId>core</artifactId>
    </dependency>
  </dependencies>
</project>
""")
    result = scan_dependencies(str(tmp_path))
    core = next(d for d in result["dependencies"] if d["name"].endswith("core"))
    assert core["version"] == "1.2.0"  # 不回退到 parent 的 2.5.14


def test_no_dependency_files_available_false(tmp_path: Path):
    (tmp_path / "main.py").write_text("print(1)\n", encoding="utf-8")
    result = scan_dependencies(str(tmp_path))
    assert result["available"] is False
    assert result["dependencies"] == []


# ---- EOL 判定 ----

def test_eol_rules_match_known_versions():
    r = find_eol_rule("maven", "org.springframework.boot:spring-boot", "2.5.14")
    assert r is not None and r.risk == "HIGH" and r.latest == "3.2+"
    r = find_eol_rule("npm", "vue", "2.6.14")
    assert r is not None
    r = find_eol_rule("npm", "node", "16.20.2")
    assert r is not None
    r = find_eol_rule("pip", "python", "3.8")
    assert r is not None


def test_eol_unknown_version_returns_none():
    # 未命中规则 → None（不误报）
    assert find_eol_rule("maven", "com.demo:core", "1.2.0") is None
    assert find_eol_rule("npm", "axios", "1.7.0") is None
    assert find_eol_rule("npm", "vue", "3.4.0") is None  # Vue 3 不在 EOL 表


def test_version_major_extracts():
    assert version_major("2.5.14") == "2"
    assert version_major("v1.2.3-beta") == "1"
    assert version_major(None) is None
    assert version_major("latest") is None


def test_dependency_marks_eol_in_scan(tmp_path: Path):
    _write(tmp_path, "pom.xml", """
<project>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
      <version>2.5.14</version>
    </dependency>
  </dependencies>
</project>
""")
    result = scan_dependencies(str(tmp_path))
    web = result["dependencies"][0]
    assert web["risk"] == "HIGH"
    assert web["isEol"] is True
    assert web["latest"] == "3.2+"


# ---- 端点 ----

def test_dependency_endpoint_ok(tmp_path: Path):
    _write(tmp_path, "pom.xml", """
<project>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
      <version>2.5.14</version>
    </dependency>
  </dependencies>
</project>
""")
    resp = client.post("/analyze/v1/dependency",
                       json={"projectId": 1, "codeDir": str(tmp_path)})
    assert resp.status_code == 200
    body = resp.json()
    assert body["available"] is True
    assert len(body["dependencies"]) == 1
    assert body["dependencies"][0]["risk"] == "HIGH"


def test_dependency_endpoint_available_false(tmp_path: Path):
    resp = client.post("/analyze/v1/dependency",
                       json={"projectId": 1, "codeDir": str(tmp_path)})
    assert resp.status_code == 200
    assert resp.json()["available"] is False
    assert resp.json()["dependencies"] == []


def test_dependency_endpoint_dir_missing():
    resp = client.post("/analyze/v1/dependency",
                       json={"projectId": 1, "codeDir": "C:/not/exist/dir"})
    assert resp.status_code == 404
