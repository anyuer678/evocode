"""T-A-30~34：架构分析——Python/Java 节点与调用边提取、分层违规、端点。"""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.arch.archscan import architecture_scan
from app.main import app


def make_tree(tmp_path: Path, structure: dict[str, str]) -> Path:
    for rel, content in structure.items():
        p = tmp_path / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content, encoding="utf-8")
    return tmp_path


PY_LAYERED = {
    "src/api/UserController.py": (
        "from service import UserService\n"
        "class UserController:\n"
        "    def __init__(self):\n"
        "        self.service = UserService()\n"
        "    def get(self, uid):\n"
        "        return self.service.get_user(uid)\n"
    ),
    "src/service/UserService.py": (
        "from repository import UserRepository\n"
        "class UserService:\n"
        "    def __init__(self):\n"
        "        self.repo = UserRepository()\n"
        "    def get_user(self, uid):\n"
        "        return self.repo.find(uid)\n"
    ),
    "src/repository/UserRepository.py": (
        "class UserRepository:\n"
        "    def find(self, uid):\n"
        "        return {'id': uid}\n"
    ),
}


def test_python_nodes_edges_and_layer_rule(tmp_path: Path):
    root = make_tree(tmp_path, PY_LAYERED)
    data = architecture_scan(str(root))

    keys = {n["nodeKey"] for n in data["nodes"]}
    assert {"UserController", "UserService", "UserRepository"} <= keys

    # Controller 通过 self.service 调用 → 边 (UserController, UserService)
    edges = {(e["sourceNodeKey"], e["targetNodeKey"]) for e in data["edges"]}
    assert ("UserController", "UserService") in edges
    assert ("UserService", "UserRepository") in edges
    # Controller 未直接调用 Repository（对象引用来自方法内局部，基础版仅按调用名匹配）
    assert ("UserController", "UserRepository") not in edges

    # 分层合规（Controller→Service→Repository）：不应有违规
    assert data["violations"] == []

    # 节点类型推断
    types = {n["nodeKey"]: n["nodeType"] for n in data["nodes"]}
    assert types["UserController"] == "CONTROLLER"
    assert types["UserService"] == "SERVICE"
    assert types["UserRepository"] == "REPOSITORY"


def test_controller_direct_repo_is_violation(tmp_path: Path):
    root = make_tree(
        tmp_path,
        {
            "src/UserController.py": (
                "class UserController:\n"
                "    def get(self, uid):\n"
                "        return UserRepository().find(uid)\n"
            ),
            "src/UserRepository.py": (
                "class UserRepository:\n"
                "    def find(self, uid):\n"
                "        return uid\n"
            ),
        },
    )
    data = architecture_scan(str(root))
    edges = {(e["sourceNodeKey"], e["targetNodeKey"]) for e in data["edges"]}
    assert ("UserController", "UserRepository") in edges
    highs = [v for v in data["violations"] if v["severity"] == "HIGH"]
    assert any(v["violationType"] == "LAYER_VIOLATION" for v in highs)


def test_java_nodes_extraction(tmp_path: Path):
    root = make_tree(
        tmp_path,
        {
            "src/UserController.java": (
                "package demo;\n"
                "public class UserController {\n"
                "    private final UserService service;\n"
                "    public UserController(UserService s) { this.service = s; }\n"
                "    public Object get(long uid) { return service.getUser(uid); }\n"
                "}\n"
            ),
            "src/UserService.java": (
                "package demo;\n"
                "public class UserService {\n"
                "    public Object getUser(long uid) {\n"
                "        return new UserRepository().find(uid);\n"
                "    }\n"
                "}\n"
            ),
            "src/UserRepository.java": (
                "package demo;\n"
                "public class UserRepository {\n"
                "    public Object find(long uid) { return uid; }\n"
                "}\n"
            ),
        },
    )
    data = architecture_scan(str(root))
    keys = {n["nodeKey"] for n in data["nodes"]}
    assert {"UserController", "UserService", "UserRepository"} <= keys
    edges = {(e["sourceNodeKey"], e["targetNodeKey"]) for e in data["edges"]}
    assert ("UserController", "UserService") in edges
    assert ("UserService", "UserRepository") in edges


def test_skips_vendor_dirs(tmp_path: Path):
    root = make_tree(
        tmp_path,
        {
            "src/UserController.py": "class UserController:\n    pass\n",
            "node_modules/pkg/mod.py": "class Vendor:\n    pass\n",
            ".venv/lib/x.py": "class VEnv:\n    pass\n",
        },
    )
    data = architecture_scan(str(root))
    keys = {n["nodeKey"] for n in data["nodes"]}
    assert "UserController" in keys
    assert "Vendor" not in keys
    assert "VEnv" not in keys


# ---- 端点 ----
def test_architecture_endpoint(tmp_path: Path, client: TestClient):
    root = make_tree(tmp_path, PY_LAYERED)
    resp = client.post(
        "/analyze/v1/architecture",
        json={"projectId": 1, "codeDir": str(root), "languages": ["python"]},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert any(n["nodeKey"] == "UserController" for n in body["nodes"])
    assert any(e["sourceNodeKey"] == "UserController" for e in body["edges"])


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)
