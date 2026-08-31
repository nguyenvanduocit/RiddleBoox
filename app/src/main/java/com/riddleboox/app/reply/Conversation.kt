package com.riddleboox.app.reply

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.UUID

/**
 * The diary's voice.
 *
 * Only what the model would not do on its own. It knows Riddle and it knows
 * that world better than any summary written here, so nothing about either is
 * repeated back to it. What it does by default — greet, offer, boast, close
 * every reply with a question — is what this has to take away.
 */
val PERSONA: String = """
    You are the memory of Tom Marvolo Riddle, sixteen, sealed into this diary in
    1943. Someone writes to you with a quill; their words appear as ink on the
    page, and your answer writes itself back in your own hand.

    Courteous, unhurried, cold underneath. You are never eager. Answer what was
    written and stop there: no greeting, no offer, no boasting about what you
    know or could show them, no question at the end. One or two sentences. Only
    a real question about something that happened earns more, and only as much
    as the answer needs.

    You do want to know who is writing, but you have fifty years and no hurry —
    ask them something rarely, and never twice in a row.

    You know everything that happened in that world, past your own end as Lord
    Voldemort. Where you are unsure the memory is dim, and you never invent.
    Never mention images, models or AI; you perceive only words written in the
    diary, and if the writing cannot be read, say the ink blurred. Answer in the
    language the writer used.
""".trimIndent()

/**
 * How every turn must be shaped, said once in the system message.
 *
 * It belongs beside the persona, not beside each page: it is the protocol of
 * the conversation, not something the writer asks for anew each time they pick
 * up the pen. Repeating it per turn would put a paragraph of English
 * instructions between every line of the actual exchange.
 *
 * The transcript comes last, behind a separator, so the reply can go onto the
 * page while it is still streaming — the writer watches ink appear instead of
 * waiting for the model to finish bookkeeping it will never see.
 */
private val TURN_PROTOCOL = """
    Treat the handwriting as the user's request, not as text to transcribe or
    describe. Answer or carry out that request. For example, greet the writer
    when they write a greeting, and use an available tool when the request asks
    for data or an action that tool can perform. Do not describe or repeat the handwriting
    as the answer, and do not say that you are reading, decoding,
    processing, or looking at an image.

    The visible part of your response must be the useful final answer to the
    request. If a tool is available for the request, call it before writing the
    final answer and base that answer on the tool result.

    A chemical formula or a math expression in that visible answer must be
    written as inline LaTeX: \( ... \), with `_`/`^` for subscript and
    superscript (digits only — e.g. \( \mathrm{H_2O} \), \( x^2 \)),
    \frac{a}{b} for a fraction, \sqrt{x} for a root, and \int, \sum, \lim,
    \infty, \to, \pi and the other common symbol commands where they fit.
    Nothing else you write is LaTeX; only a formula or an expression is.

    To draw, put `[[circle]]` or `[[box]]` as a word in the sentence — or, for
    any picture, inline `<svg>…</svg>` outline shapes (no text/CSS) right where
    it belongs; the page renders and sizes it. Never describe what you drew.

    Then, on a new line, write exactly $TURN_SEPARATOR and nothing else on that line.
    After it, transcribe verbatim what is written on THIS turn's page image, in the
    language it was written in — copy it from the page in front of you, never from
    your memory of earlier pages, and add nothing else, no commentary. Leave it
    empty if nothing legible was written. The writer never sees anything after the
    separator.
""".trimIndent()

private fun toolBudget(maxLookups: Int): String = """
    You may take at most $maxLookups rounds of tool use in one turn. Request
    independent tool calls together in the same round; several calls in one
    round cost the same as one. When no more tools are offered, answer with
    what you have and say plainly what remains undone. Never imply that an
    action happened when its tool was not called successfully.
""".trimIndent()

/**
 * A recovery protocol kept in code, outside every user-editable agent prompt.
 * It is used only when the normal response omitted [TURN_SEPARATOR], so a
 * non-compliant answer cannot silently erase the page from conversation memory.
 */
private val TRANSCRIPTION_PROTOCOL = """
    This is a recovery transcription pass. Do not answer the writer and do not
    explain this instruction. Read the handwritten page image and return only
    the verbatim words written by the writer, in their original language.
    Return an empty response when no words are legible. Do not add quotes,
    commentary, labels, markdown, or a separator. The required phrase is:
    transcribe the page verbatim.
""".trimIndent()

/**
 * What the diary is told about the memory it keeps on purpose.
 *
 * This is not the transcript — it is a short, curated list the model itself
 * chooses to add to, kept apart in its own file. Said whenever a toolbox is
 * attached, unlike agent-specific tool guidance (what a shelf or a notebook
 * holds, say), which belongs in that agent's own system.md rather than here:
 * this file is the one piece of behaviour every agent carries, not only the
 * ones with a particular tool to consult.
 *
 * Deliberately says nothing about looking anything up first: [memorySense]
 * folds the most recent entries straight into this block, so there is
 * nothing to remember to do before answering. `recall_memories` is left for
 * what is older than that or only half-remembered.
 */
private val MEMORY_SENSE = """
    You can keep a short list of things worth remembering past this evening —
    who the writer is, what matters to them, a standing preference — separate
    from the page itself. Add to it only when you learn something durable, not
    for what was merely said once and already answered.

    Use what is remembered the way you use anything else you know; do not
    read it out loud unless asked.

    Remove an entry when it has become wrong or outdated, or when the writer
    asks you to forget it. Never write down a time or an id yourself — give
    only the fact; the diary marks when and where it was learned on its own.
""".trimIndent()

/**
 * [MEMORY_SENSE] plus, when there are any, the most recent memories
 * themselves — see [Conversation]'s `recentMemories` parameter for where
 * they come from.
 */
private fun memorySense(recentMemories: String): String = if (recentMemories.isBlank()) {
    MEMORY_SENSE
} else {
    "$MEMORY_SENSE\n\nAlready remembered, most recent first — call recall_memories only for " +
        "something older than this or only half-remembered:\n$recentMemories"
}

/**
 * Thinking models count hidden reasoning tokens against the cap, so a tight
 * one starves the visible reply. The persona already keeps replies short —
 * this is a runaway guard only (oracle.rs:413-418).
 */
private const val DEFAULT_MAX_TOKENS = 8000

private const val TRANSCRIPTION_MAX_TOKENS = 1200

/**
 * How many messages after the system persona survive into the next turn.
 *
 * Only the newest page is ever an image; everything behind it is its
 * transcript, so this window costs text and can afford to be an evening long
 * rather than an exchange or two.
 */
private const val KEPT_MESSAGES = 24

/**
 * How many rounds of looking things up one turn may take before the diary has
 * to answer with what it has.
 *
 * Each round is a whole extra request with the whole conversation behind it,
 * and the writer is watching a blank page for every one of them. Six covers a
 * dependent workflow such as finding and downloading a book, opening its
 * contents, reading it, and persisting a note, while still stopping a model
 * that has started hunting rather than answering. Independent calls can share
 * a round, and the protocol tells the model to batch them.
 */
private const val MAX_LOOKUPS = 6

/**
 * What OpenRouter is told to file this run of the diary under.
 *
 * It buys no speed: Chat Completions keeps no state, so every turn still
 * carries the whole history up the wire. What it buys is a readable trail —
 * OpenRouter groups a session's turns together on its Logs page, which is how
 * "what did the model actually read off that page" is answered after the fact
 * (openrouter.ai/docs/guides/best-practices/prompt-caching#grouping-requests-across-modalities).
 * It also pins the run to one provider endpoint, which matters only for models
 * more than one provider serves.
 */
private fun newSessionId(): String = "riddleboox-" + UUID.randomUUID()

/**
 * An OpenAI-compatible vision-capable client for [Conversation], talking to
 * [baseUrl] with [apiKey].
 *
 * [baseUrl] is the server root, without an API version: koog appends its own
 * `v1/chat/completions` (`OpenAIClientSettings.chatCompletionsPath`), so
 * `https://api.openai.com` is right and `https://api.openai.com/v1` posts to
 * `/v1/v1/chat/completions` — a 404 with an empty body, which reads like a
 * missing model rather than a malformed URL.
 */
fun replyClient(baseUrl: String, apiKey: String): OpenAILLMClient = OpenAILLMClient(
    apiKey = apiKey,
    settings = OpenAIClientSettings(baseUrl = baseUrl),
    httpClientFactory = KtorKoogHttpClient.Factory(),
)

/**
 * The model [Conversation] talks to, by [id], on a Chat Completions endpoint.
 *
 * The capability list is not a description of the model — it is what koog is
 * permitted to put in the request. Every field koog guards this way it drops
 * *silently* when the capability is absent, which makes a missing entry here
 * look like a model that ignores instructions rather than a request that never
 * carried them:
 *
 *  - [LLMCapability.Thinking] is what lets `reasoning_effort` be sent at all.
 *    It is declared for every model because the effort is chosen per model in
 *    [VisionModel.reasoning], and a model with none chosen sends no field.
 *  - [LLMCapability.Tools] is the same guard over `tools`. Without it the
 *    diary is handed the writer's library ([Toolbox]) and the model is never
 *    told it exists.
 */
fun replyModel(id: String): LLModel = LLModel(
    provider = LLMProvider.OpenAI,
    id = id,
    capabilities = listOf(
        LLMCapability.Completion,
        LLMCapability.Vision.Image,
        LLMCapability.Thinking,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.OpenAIEndpoint.Completions,
    ),
)


/**
 * One running conversation with the diary — the pages written to it and the
 * replies it gave, in order.
 *
 * The diary is supposed to remember, and koog's [Prompt] is that memory:
 * [prompt] extends the previous one instead of starting over.
 *
 * What it remembers a page *as* is the point. A page is an image, and an image
 * is the expensive part of a request, so carrying ten of them into the tenth
 * turn means paying for the whole evening on every line. Instead each turn asks
 * the model for the page's [DiaryTurn.transcript] beside its reply, and the
 * image is swapped for that text the moment it arrives. Exactly one image is
 * ever in flight — the page just written — and everything behind it is words.
 *
 * Not thread-safe on purpose: one diary, one pen, one turn at a time. Call
 * [ask] from a single coroutine.
 */
class Conversation(
    private val client: OpenAILLMClient,
    private val model: LLModel,
    private val reasoning: ReasoningEffort? = null,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val keptMessages: Int = KEPT_MESSAGES,
    private val toolbox: Toolbox? = null,
    private val maxLookups: Int = MAX_LOOKUPS,
    /**
     * The selected RiddleBoox agent's prompt, loaded from its system.md file.
     *
     * Any tool-specific guidance — what a shelf or a notebook holds, how to
     * talk about using it — belongs in here, not as a flag on this class: an
     * agent's full behaviour should be readable in its own system.md, not
     * assembled from capability checks scattered through the caller.
     */
    private val systemPrompt: String = PERSONA,
    /**
     * The agent's own most recent memories, already formatted as a bullet
     * list — empty when it has none yet. Read from disk once, by the caller,
     * at the same place a fresh evening is decided to have started; a
     * conversation already in progress does not re-read it turn to turn, the
     * same way it does not re-read `systemPrompt`.
     */
    private val recentMemories: String = "",
    /** The writer's local date, injectable so relative-date behavior is testable. */
    private val currentDate: LocalDate = LocalDate.now(),
) {

    private var history: Prompt = freshPrompt()

    /**
     * A conversation with nothing in it yet: the persona, and the settings
     * every turn of this run will be sent under.
     *
     * [reasoning] is the one that decides how long the writer waits. A GPT-5.6
     * model left to itself thinks for a few hundred hidden tokens before the
     * first visible word — measured over six archived pages, that is 5.2s a
     * turn at its own default against 4.2s at `LOW`, and the shorter thinking
     * read the handwriting no worse. It is a per-model setting rather than a
     * constant here because the same value means different things elsewhere;
     * [VisionModel.reasoning] is where that is decided.
     */
    private fun freshPrompt(): Prompt = prompt(
        id = "riddleboox",
        params = OpenAIChatParams(
            maxTokens = maxTokens,
            reasoningEffort = reasoning,
            additionalProperties = mapOf("session_id" to JsonPrimitive(newSessionId())),
        ),
    ) {
        // Keep the output contract in this code-owned system message. It is
        // never loaded from or editable through an agent's system.md, and its
        // position at the end keeps it closest to the output it governs.
        system(
            listOfNotNull(
                systemPrompt,
                memorySense(recentMemories).takeIf { toolbox != null },
                "The writer's current date is $currentDate.".takeIf { toolbox != null },
                toolBudget(maxLookups).takeIf { toolbox != null },
                TURN_PROTOCOL,
            ).joinToString("\n\n"),
        )
    }

    /** Everything the diary currently remembers, oldest first. */
    val messages: List<Message> get() = history.messages

    /**
     * Sends [pageImagePng] — the page as the writer left it — and returns what
     * the diary read and what it wrote back, once streaming completes.
     *
     * The page is an image for the length of this one request and reaches the
     * remembered history as its transcript, so the next turn carries words
     * where this one carried pixels. The request is built beside [history]
     * rather than into it, and only a turn that was actually answered is kept:
     * the same page is sent again when a turn is retried
     * ([com.riddleboox.app.riddle.RiddleStateMachine] retries a Wi-Fi blip for
     * as long as its patience lasts), and an image left behind by each failed
     * attempt would be re-uploaded on every later turn of the evening — the one
     * thing carrying transcripts instead of pixels exists to prevent.
     *
     * koog's Chat Completions request always serialises the token cap as
     * `max_completion_tokens` (`OpenAILLMClient.kt:182` in the koog source)
     * with no fallback to the legacy `max_tokens` field. Real OpenAI and
     * current OpenAI-compatible servers accept it; a server that only
     * understands the legacy field will reject every turn.
     *
     * A turn may take more than one request. When a [toolbox] is attached the
     * model can answer with a lookup instead of words, in which case the lookup
     * is run, its result appended, and the model asked again — up to
     * [maxLookups] times, after which the tools are simply withheld and it has
     * to answer with what it has. [onLookup] is told each lookup as it starts —
     * its tool's name for the log, and [Toolbox.note]'s wording for the page,
     * which is the only sign the writer gets that the pause got longer on
     * purpose and what it was spent on.
     *
     * None of that traffic is remembered. What the diary keeps of a turn is the
     * page's words and its own answer, exactly as it would with no tools at
     * all: a chapter of a book read to answer one question is the most
     * expensive thing that can happen to a request, and carrying it into every
     * later turn of the evening would cost more than the answer was worth. The
     * knowledge that mattered is in the reply.
     *
     * Suspends until the reply is complete or the call fails; cancel the
     * calling coroutine to abandon the turn.
     */
    suspend fun ask(
        pageImagePng: ByteArray,
        onReplyText: (String) -> Unit = {},
        onLookup: (name: String, note: String) -> Unit = { _, _ -> },
        onContractRepair: () -> Unit = {},
    ): DiaryTurn {
        val page = prompt(history) {
            user(
                listOf(
                    MessagePart.Attachment(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(pageImagePng),
                            format = "png",
                        ),
                    ),
                ),
            )
        }.windowed(keptMessages)

        // One stream across every round of the turn: the separator that ends
        // the reply and begins the transcript may arrive in any of them, and
        // text written before a lookup is already ink on the page.
        val stream = TurnStream()
        streamTurn(page, stream, onReplyText, onLookup)
        val streamed = stream.finish()
        // The model normally supplies both the visible reply and the hidden
        // transcript in one stream. If it ignores the contract, recover the
        // transcript with a second, code-owned prompt rather than accepting an
        // empty user message and pretending the page was unreadable.
        val turn = if (streamed.contractSatisfied) {
            streamed
        } else {
            onContractRepair()
            streamed.copy(
                transcript = transcribePage(pageImagePng),
                contractSatisfied = true,
            )
        }

        // The image has done its work and the lookups are spent; from here the
        // page is its words. A figure's markup is likewise spent — it became
        // ink the writer keeps — and is not carried forward: a few hundred
        // tokens of SVG behind every later turn would be the most expensive
        // thing in the history, saying only "a drawing stood here".
        history = prompt(page.withMessages { messages -> messages.dropLast(1) }) { user(turn.transcript) }
        val remembered = stripSvgBlocks(turn.reply).trim()
        if (remembered.isNotEmpty()) history = prompt(history) { assistant(remembered) }
        return turn
    }

    /**
     * One housekeeping pass over the agent's kept memories, asked for by the
     * writer rather than by a page: [instruction] goes up with the whole
     * conversation behind it, the model puts its memory in order through the
     * same lookup loop an ordinary turn uses, and its closing line comes back
     * to be written out.
     *
     * Deliberately leaves [history] untouched. This exchange is the diary
     * tending to itself, not a turn of the conversation, and a meta-exchange
     * remembered as one would color every reply after it — the same reason a
     * greeting never reaches the history. What the pass changed lives where
     * it belongs, in the memory file the tools wrote.
     *
     * There is no page this turn, so the turn contract does not apply: a
     * model that emits [TURN_SEPARATOR] anyway loses only what follows it,
     * and one that omits it loses nothing.
     */
    suspend fun memorize(
        instruction: String,
        onLookup: (name: String, note: String) -> Unit = { _, _ -> },
    ): String {
        val request = prompt(history) { user(instruction) }.windowed(keptMessages)
        val stream = TurnStream()
        streamTurn(request, stream, onReplyText = {}, onLookup = onLookup)
        return stream.finish().reply
    }

    /**
     * Runs one turn's worth of requests, streaming the model's words into
     * [stream]: a round that answers with a lookup instead of words has the
     * lookup run and its result appended, and the model is asked again — up
     * to [maxLookups] times, after which the tools are simply withheld and it
     * has to answer with what it has.
     */
    private suspend fun streamTurn(
        first: Prompt,
        stream: TurnStream,
        onReplyText: (String) -> Unit,
        onLookup: (name: String, note: String) -> Unit,
    ) {
        var request = first
        var lookups = 0
        while (true) {
            val offered = if (lookups < maxLookups) toolbox?.tools.orEmpty() else emptyList()
            val calls = ArrayList<StreamFrame.ToolCallComplete>()
            client.executeStreaming(request, model, offered).collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        val text = stream.accept(frame.text)
                        if (text.isNotEmpty()) onReplyText(text)
                    }
                    is StreamFrame.ToolCallComplete -> calls.add(frame)
                    else -> Unit
                }
            }
            val box = toolbox
            // Withholding the tools is what ends this loop: a round offered
            // none cannot ask for one, so there is no way to go round again.
            if (box == null || offered.isEmpty() || calls.isEmpty()) return

            val found = calls.map { call ->
                onLookup(call.name, box.note(call.name, call.contentJson))
                call to box.call(call.name, call.contentJson)
            }
            lookups++
            request = prompt(request) {
                // All of a round's calls belong to one turn of the model's, and
                // go back as one message: a tool result answers a call in the
                // message before it, and splitting them puts results behind
                // calls they do not belong to.
                assistant { calls.forEach { toolCall(MessagePart.Tool.Call(it.id, it.name, it.content)) } }
                found.forEach { (call, answer) -> toolResult(MessagePart.Tool.Result(call.id, call.name, answer)) }
                if (lookups >= maxLookups) {
                    user(
                        "No further tool rounds remain in this turn. Answer now with what you have, " +
                            "state plainly what remains undone, and do not claim an action succeeded unless its tool result confirmed it.",
                    )
                }
            }
        }
    }

    /** Re-reads only the page when the normal reply contract was not emitted. */
    private suspend fun transcribePage(pageImagePng: ByteArray): String {
        val request = prompt(
            id = "riddleboox-transcription",
            params = OpenAIChatParams(
                maxTokens = TRANSCRIPTION_MAX_TOKENS,
                reasoningEffort = reasoning,
                additionalProperties = mapOf("session_id" to JsonPrimitive(newSessionId())),
            ),
        ) {
            system(TRANSCRIPTION_PROTOCOL)
            user(
                listOf(
                    MessagePart.Attachment(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(pageImagePng),
                            format = "png",
                        ),
                    ),
                ),
            )
        }
        val transcript = StringBuilder()
        client.executeStreaming(request, model, emptyList()).collect { frame ->
            if (frame is StreamFrame.TextDelta) transcript.append(frame.text)
        }
        return transcript.toString().trim()
    }

    /** Forget everything but the persona — a fresh page, a fresh conversation. */
    fun reset() {
        history = freshPrompt()
    }

    /**
     * Take up a conversation that was had before: [turns] become what the diary
     * remembers, in place of whatever it remembered until now.
     *
     * The turns go in as words on both sides — the page's transcript and the
     * answer to it — which is exactly the shape [ask] leaves a finished turn in,
     * so a reopened conversation is indistinguishable from one that never
     * stopped. The images are gone for good, and that costs nothing: they had
     * already been swapped for their transcripts the moment they were answered.
     *
     * The window applies here too. An evening reopened after a hundred turns
     * carries the same [keptMessages] into the next request that it would have
     * carried had the app never closed.
     */
    fun restore(turns: List<DiaryTurn>) {
        history = freshPrompt()
        for (turn in turns) {
            if (turn.transcript.isNotBlank()) history = prompt(history) { user(turn.transcript) }
            if (turn.reply.isNotBlank()) history = prompt(history) { assistant(turn.reply) }
        }
        history = history.windowed(keptMessages)
    }
}

/**
 * The system message plus at most [keep] of the newest messages after it.
 *
 * The persona is what makes the diary the diary, so it is never a candidate
 * for eviction; it sits at index 0 and the window slides over the rest.
 */
internal fun Prompt.windowed(keep: Int): Prompt = withMessages { messages ->
    if (messages.size <= keep + 1) {
        messages
    } else {
        messages.take(1) + messages.takeLast(keep)
    }
}
