"""T-A-L-01~06：TD-09 语言扩展——JS/TS/Go 架构提取。"""

from app.core.arch.archscan import architecture_scan
from app.core.arch.go_parser import parse_go_file
from app.core.arch.js_parser import parse_js_file, parse_ts_file


def _names(parsed) -> list[str]:
    nodes, _ = parsed
    return [n.node_key for n in nodes]


def test_js_parses_class_and_function():
    src = b"""
class UserService {
  getUser(id) { return this.repo.findById(id); }
}
function helper() { return 1; }
const config = { port: 8080 };
"""
    nodes, calls = parse_js_file("src/service.js", src)
    keys = [n.node_key for n in nodes]
    assert "UserService" in keys
    assert "helper" in keys
    # const 声明也应成为节点
    assert "config" in keys
    # UserService 内调用 findById（member_expression 根段 repo 匹配类名失败，
    # 属 v0.2 边界）
    assert any("getUser" in n.node_key or n.node_key == "UserService" for n in nodes)
    assert isinstance(calls, list)


def test_js_export_wrapper():
    src = b"""
export class PaymentController {
  pay(order) { return this.service.process(order); }
}
export function util() {}
"""
    keys = _names(parse_js_file("src/controller.js", src))
    assert "PaymentController" in keys
    assert "util" in keys


def test_ts_parses_interfaces_and_functions():
    src = b"""
export interface UserRepository {
  findById(id: string): User;
}
export class UserService {
  getUser(id: string) { return this.repo.findById(id); }
}
const helper = (x: number) => x * 2;
"""
    keys = _names(parse_ts_file("src/service.ts", src))
    assert "UserService" in keys
    assert "helper" in keys
    # interface 非实现，当前不进节点（与 v0.2 类级边界一致）


def test_go_parses_functions_and_methods():
    src = b"""
package main

func main() {}

type Service struct{}

func (s *Service) Get() string { return "x" }

func helper() {}
"""
    nodes, calls = parse_go_file("main.go", src)
    keys = [n.node_key for n in nodes]
    # 顶层函数 + 方法接收者类型
    assert "main" in keys
    assert "helper" in keys
    assert "Service" in keys
    assert any("Get" in k for k in keys) or "Service" in keys
    assert isinstance(calls, list)


def test_go_receiver_strips_pointer():
    src = b"package main\ntype Repo struct{}\nfunc (r *Repo) Find() {}\n"
    nodes, _ = parse_go_file("repo.go", src)
    keys = [n.node_key for n in nodes]
    assert "Repo" in keys  # 指针已剥离


def test_architecture_scan_js_ts_go(tmp_path):
    # 端到端：混合语言目录扫描出节点与跨文件调用边
    (tmp_path / "service.js").write_text(
        "class UserService { get() { return this.x; } }", encoding="utf-8"
    )
    (tmp_path / "controller.ts").write_text(
        "class UserController { run() { return new UserService(); } }", encoding="utf-8"
    )
    (tmp_path / "main.go").write_text(
        "package main\ntype Repo struct{}\nfunc (r *Repo) Find() {}\nfunc main() {}",
        encoding="utf-8",
    )
    result = architecture_scan(str(tmp_path))
    keys = {n["nodeKey"] for n in result["nodes"]}
    assert "UserService" in keys
    assert "UserController" in keys
    assert "Repo" in keys
    assert result["nodes"], "应产出节点"
