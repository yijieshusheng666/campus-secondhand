from app.llm import LLM, LLMUnavailable
from app.prompts import build_structured_answer
from app.retrieval import Hit


def _hit(id_, title, content, shard="faq", source="高频 FAQ·购买类", confidence=0.9):
    return Hit(id=id_, title=title, content=content, source=source, shard=shard,
               confidence=confidence)


def test_without_api_key_mode_is_extractive(monkeypatch):
    from app import config
    monkeypatch.setattr(config.settings, "llm_base_url", "")
    monkeypatch.setattr(config.settings, "llm_api_key", "")
    monkeypatch.setattr(config.settings, "llm_model", "")
    llm = LLM()
    assert llm.mode() == "extractive"
    assert llm.available() is False


def test_generate_raises_when_unavailable(monkeypatch):
    from app import config
    monkeypatch.setattr(config.settings, "llm_base_url", "")
    monkeypatch.setattr(config.settings, "llm_api_key", "")
    monkeypatch.setattr(config.settings, "llm_model", "")
    llm = LLM()
    try:
        llm.generate("你好")
        assert False, "不可用时应抛 LLMUnavailable"
    except LLMUnavailable:
        pass


def test_extractive_answer_is_grounded():
    """抽取式答案必须逐字来自知识库，且正文不掺「依据」标注。"""
    hits = [
        _hit("F011", "可以只搜教材吗", "可以。点「图书教材」分类胶囊就只看教材。"),
        _hit("R003", "退款规则", "第1步 在订单里点申请退款。\n第2步 卖家同意后退回。", shard="rules"),
    ]
    answer = build_structured_answer(hits)
    assert "可以。点「图书教材」分类胶囊就只看教材。" in answer
    assert "第2步 卖家同意后退回。" in answer, "次优但不同主题的条目也应补进正文"

    # 正文不带任何溯源标注（含条目编号）；溯源由前端按 hits 单独渲染。
    # 注意：这里不能再断言 "R003" in answer —— R003 本身就是编号，与下一条互斥。
    assert "依据" not in answer, "溯源由前端单独渲染，正文不该再带依据标注"
    assert "R003" not in answer, "正文不该出现条目编号"


def test_extractive_answer_has_no_scripted_filler():
    """不能再说「根据平台规则，我找到以下相关信息」这类念稿式开场。"""
    hits = [_hit("G003", "发布流程", "第1步 打开发布页。\n第2步 填标题和价格。")]
    answer = build_structured_answer(hits)
    assert "我找到以下相关信息" not in answer
    assert not answer.startswith("根据平台")
    # 正文第一行就是答案本身，而不是标题或过程话
    assert answer.splitlines()[0] == "第1步 打开发布页。"


def test_extractive_answer_skips_same_topic_extra_hit():
    """同一分片、标题高度重叠的次优条目不必都答，避免复读。"""
    hits = [
        _hit("F010", "教材怎么定价", "参考同类在售教材的均价。"),
        _hit("F012", "教材怎么定价才合适", "带笔记的可以略高一点。"),
    ]
    answer = build_structured_answer(hits)
    assert "参考同类在售教材的均价。" in answer
    assert "带笔记的可以略高一点。" not in answer


def test_extractive_answer_when_no_hits():
    answer = build_structured_answer([])
    assert answer  # 没有命中也要给出可读回复
    assert "暂时" in answer or "没有" in answer


def test_history_block_enters_prompt():
    """多轮上下文必须进生成提示词：否则「那退款呢」这类追问在模型眼里是孤立一句。"""
    from app.prompts import RAG_QA, build_history_block
    block = build_history_block([
        {"role": "user", "content": "高数教材多少钱"},
        {"role": "assistant", "content": "参考区间 15-30 元。"},
    ])
    assert "高数教材多少钱" in block
    assert "助手：参考区间 15-30 元。" in block

    prompt = RAG_QA.format(context="- [1] F001（高频 FAQ）正文…", question="那大英呢", history=block)
    assert "高数教材多少钱" in prompt, "上一轮内容必须出现在提示词里"
    assert "那大英呢" in prompt


def test_history_block_empty_when_no_history():
    from app.prompts import build_history_block
    assert build_history_block([]) == ""
    assert build_history_block(None) == ""
    # 全是空白内容时也不该拼出一个空的「最近的对话」壳子
    assert build_history_block([{"role": "user", "content": "   "}]) == ""


def test_prompts_have_no_retired_feature_copy():
    """已下线的功能不能还留在提示词里教用户怎么用。"""
    import inspect

    from app import prompts
    source = inspect.getsource(prompts)
    for word in ("课程号", "换物", "以物换物"):
        assert word not in source, f"提示词里还有已下线功能的说法：{word}"
