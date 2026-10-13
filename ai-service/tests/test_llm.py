from app.llm import LLM, LLMUnavailable
from app.prompts import build_extractive_answer


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


def test_extractive_answer_contains_snippets():
    answer = build_extractive_answer(["在售商品支持退款。", "待发货订单可申请退款。"])
    assert "在售商品支持退款。" in answer
    assert "待发货订单可申请退款。" in answer


def test_extractive_answer_when_no_context():
    answer = build_extractive_answer([])
    assert answer  # 空上下文也要给出可读回复
    assert "暂时" in answer or "没有" in answer
