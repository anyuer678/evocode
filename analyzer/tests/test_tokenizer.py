"""T-T-01~05：TD-10 token 估算——规则正确性、单调性、预算切分。"""

from app.core.tokenizer import estimate_tokens, split_at_token_budget


def test_estimate_english_word_ratio():
    # 英文单词 ≈ ceil(len/4)
    assert estimate_tokens("hello") == 2      # 5 字符 → ceil(5/4)=2
    assert estimate_tokens("a") == 1          # 最短 1
    assert estimate_tokens("code") == 1       # 4 字符 → 1


def test_estimate_cjk_higher_than_ascii():
    # 中文逐字符 1.5 token（保守），同长度下应高于英文
    zh = estimate_tokens("中文测试文本")
    en = estimate_tokens("abcdabcdab")
    assert zh > en


def test_estimate_monotonic():
    # 前缀越长 token 越多（单调不减）——split 依赖此性质
    text = "hello world 中文 测试 some_code_here 12345"
    prev = 0
    for i in range(1, len(text) + 1):
        t = estimate_tokens(text[:i])
        assert t >= prev
        prev = t


def test_estimate_empty():
    assert estimate_tokens("") == 0


def test_split_at_token_budget():
    text = "hello " * 200  # 1000 chars, ~250 token
    cut = split_at_token_budget(text, 100)
    assert 0 < cut <= len(text)
    assert estimate_tokens(text[:cut]) <= 100
    # 前缀超预算则更小的切点也超预算
    assert estimate_tokens(text[: cut + 1]) > 100 or cut == len(text)


def test_split_budget_covers_whole_when_small():
    text = "short"
    assert split_at_token_budget(text, 1000) == len(text)


def test_estimate_punctuation_and_leftover():
    # 标点串 ceil(len/2)；emoji 等未覆盖字符按 1/字符兜底
    assert estimate_tokens("!!!") == 2          # 3 个标点 → ceil(3/2)=2
    assert estimate_tokens("a,b,c") > estimate_tokens("abc")
    assert estimate_tokens("hello 😀") >= 2      # 单词 + emoji 兜底


def test_estimate_cjk_budget_not_exceeded():
    # 中文片 ≤400 token：400 token ≈ 267 字（1.5/字），断言滑切后每片不超预算
    text = "中" * 2000  # 2000 字 → 3000 token，远超 400
    cut = split_at_token_budget(text, 400)
    assert estimate_tokens(text[:cut]) <= 400
    assert estimate_tokens(text[: cut + 1]) > 400
