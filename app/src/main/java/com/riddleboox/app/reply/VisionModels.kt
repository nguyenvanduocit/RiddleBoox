package com.riddleboox.app.reply

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort

/**
 * One model the diary can be pointed at.
 *
 * [id] is what goes on the wire; [label] and [note] are what the writer picks
 * between — a bare `openai/gpt-5.6-luna` says nothing about whether it can read
 * handwriting or what it costs to find out.
 *
 * [reasoning] is how long it may think before the first word of the answer
 * appears, and it belongs to the model rather than to the app: the same value
 * means opposite things across houses. `LOW` on a GPT-5.6 model cuts thinking
 * that was happening anyway; on Claude, which does not think unless asked, it
 * would switch thinking *on* and cost seconds. Gemini refuses to turn thinking
 * off at all. `null` is therefore not "no thinking" but "whatever this model
 * does by default", which is the only safe answer for a model nobody measured.
 */
data class VisionModel(
    val id: String,
    val label: String,
    val note: String,
    val reasoning: ReasoningEffort? = null,
)

/**
 * The shortlist offered in settings — models that can read a page, one or two
 * per house, cheapest first within each.
 *
 * A shortlist rather than the provider's whole catalogue: OpenRouter carries
 * 245 vision models, and picking between them on a stylus tablet is not a
 * decision anyone wants to scroll. Notes come from reading the same handwritten
 * Vietnamese page with each — the thing this diary actually asks a model to do,
 * and the thing the model cards do not tell you.
 */
val VISION_MODELS: List<VisionModel> = listOf(
    VisionModel(
        id = "openai/gpt-5.6-luna",
        label = "GPT-5.6 Luna",
        note = "đọc chữ tay chuẩn nhất, giữ giọng nhật ký (~4s)",
        reasoning = ReasoningEffort.LOW,
    ),
    VisionModel(
        id = "openai/gpt-5.6-terra",
        label = "GPT-5.6 Terra",
        note = "mạnh hơn Luna, đắt gấp 10",
        reasoning = ReasoningEffort.LOW,
    ),
    VisionModel(
        id = "google/gemini-3.7-flash",
        label = "Gemini 3.7 Flash",
        note = "đọc tốt, nhanh (~5s) — hay tuột khỏi vai, xưng “tôi/bạn”",
        reasoning = ReasoningEffort.LOW,
    ),
    VisionModel(
        id = "google/gemini-3.5-flash",
        label = "Gemini 3.5 Flash",
        note = "đọc chuẩn, chậm nhất nhóm (~10s)",
        reasoning = ReasoningEffort.LOW,
    ),
    VisionModel(
        id = "anthropic/claude-sonnet-5",
        label = "Claude Sonnet 5",
        note = "mạnh, chưa đo trên chữ tay tiếng Việt",
    ),
    VisionModel(
        id = "anthropic/claude-haiku-4.5",
        label = "Claude Haiku 4.5",
        note = "nhanh, nhưng đọc rụng hết dấu tiếng Việt",
    ),
    VisionModel(
        id = "qwen/qwen3-vl-32b-instruct",
        label = "Qwen3-VL 32B",
        note = "nhanh nhất (~2s), rẻ nhất — có khi đọc đảo ý câu",
    ),
    VisionModel(
        id = "mistralai/mistral-medium-3.1",
        label = "Mistral Medium 3.1",
        note = "chưa đo trên chữ tay tiếng Việt",
    ),
)

/**
 * [VISION_MODELS], with [current] included even when it is not one of them.
 *
 * A model configured by hand — a new release, something self-hosted — must not
 * vanish from the screen just because this list has not caught up with it;
 * opening settings would silently rewrite a working setup on the next save.
 */
fun modelChoices(current: String): List<VisionModel> {
    val known = VISION_MODELS.any { it.id == current.trim() }
    if (known || current.isBlank()) return VISION_MODELS
    return VISION_MODELS + VisionModel(
        id = current.trim(),
        label = current.trim(),
        note = "đang dùng, đặt bằng tay",
    )
}

/**
 * How hard the model behind [modelId] should think before answering, or null
 * to leave that to the model.
 *
 * A model set by hand is unmeasured by definition, so it gets its own default
 * rather than a guess borrowed from whichever entry happens to look similar.
 */
fun reasoningFor(modelId: String): ReasoningEffort? =
    VISION_MODELS.firstOrNull { it.id == modelId.trim() }?.reasoning
