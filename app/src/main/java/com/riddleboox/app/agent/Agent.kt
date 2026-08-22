package com.riddleboox.app.agent

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale

/** The small, user-visible part of an agent that is kept in agent.json. */
@Serializable
data class AgentManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val builtin: Boolean = false,
    /**
     * Capability ids this agent opts into, resolved to toolboxes at runtime.
     * The default toolset (workspace, memory, drawing) is every agent's and is
     * never listed here — see [AgentCapability].
     */
    val toolIds: Set<String> = emptySet(),
    /** Short lines the diary may write when this agent opens a blank page. */
    val greetings: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
)

/** An agent plus the prompt loaded from its own file and its private workspace. */
data class AgentDefinition(
    val manifest: AgentManifest,
    val systemPrompt: String,
    val workspace: File,
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
    val description: String get() = manifest.description
    val builtin: Boolean get() = manifest.builtin
    val toolIds: Set<String> get() = manifest.toolIds
    /** A manifest that carries no greetings of its own falls back to the shared set. */
    val greetings: List<String> get() = manifest.greetings.ifEmpty { DEFAULT_AGENT_GREETINGS }
}

private const val AGENTS_DIR = "agents"
private const val MANIFEST_FILE = "agent.json"
private const val PROMPT_FILE = "system.md"
private const val WORKSPACE_DIR = "workspace"

/**
 * Persists the agents the user talks to.
 *
 * Each agent is deliberately a directory rather than a row in preferences:
 * `agent.json` describes it, `system.md` is the prompt the model receives, and
 * `workspace/` is the only filesystem visible to that agent's tools. This
 * makes prompts inspectable and leaves room for future artifact indexes without
 * changing the app's global settings format. Built-in agents are read-only;
 * user-created agents remain editable.
 */
class AgentStore(root: File) {

    constructor(context: Context) : this(context.filesDir)

    private val dir = File(root, AGENTS_DIR)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Creates the built-ins that are missing and leaves every manifest already
     * on disk alone: what an agent may reach for is the reader's choice, made
     * in the agents screen, and nothing here is allowed to overrule it.
     *
     * [load] returns null both when an agent has never existed and when its
     * folder exists but `system.md` alone is gone — a missing prompt file is
     * the one thing [load] cannot tell apart from "no such agent". Routing the
     * second case into [create] crashes: `create` refuses to write into a
     * folder that already exists. The folder's own existence is what actually
     * distinguishes them, so it is checked directly here instead.
     */
    fun ensureDefaults() {
        dir.mkdirs()
        for (default in DEFAULT_AGENTS) {
            val existing = load(default.id)
            when {
                existing != null -> {
                    existing.workspace.mkdirs()
                    if (existing.builtin && existing.manifest.greetings.isEmpty()) {
                        updateBuiltin(default.id, greetings = default.greetings)
                    }
                }
                folder(default.id).exists() -> {
                    File(folder(default.id), WORKSPACE_DIR).mkdirs()
                    writeAtomic(promptFile(default.id), default.systemPrompt.trim() + "\n")
                }
                else -> create(
                    id = default.id,
                    name = default.name,
                    description = default.description,
                    systemPrompt = default.systemPrompt,
                    builtin = true,
                    tools = default.toolIds,
                    greetings = default.greetings,
                )
            }
        }
    }

    fun list(): List<AgentDefinition> = dir.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.mapNotNull { load(it.name) }
        ?.sortedWith(compareBy<AgentDefinition> { !it.builtin }.thenBy { it.name.lowercase(Locale.ROOT) })
        ?.toList()
        .orEmpty()

    fun load(id: String): AgentDefinition? {
        if (!isValidId(id)) return null
        val folder = folder(id)
        val manifestFile = File(folder, MANIFEST_FILE)
        if (!manifestFile.isFile) return null
        val manifest = try {
            json.decodeFromString(AgentManifest.serializer(), manifestFile.readText())
        } catch (_: SerializationException) {
            return null
        } catch (_: IOException) {
            return null
        }
        if (manifest.id != id) return null
        val safeManifest = manifest.copy(
            toolIds = AgentCapability.normalize(
                requested = manifest.toolIds,
                isBuiltInManager = manifest.builtin && manifest.id == "agent-manager",
            ),
        )
        val prompt = promptFile(id)
        if (!prompt.isFile) return null
        return AgentDefinition(
            manifest = safeManifest,
            systemPrompt = runCatching { prompt.readText() }.getOrNull() ?: return null,
            workspace = File(folder, WORKSPACE_DIR).also { it.mkdirs() },
        )
    }

    fun create(
        id: String,
        name: String,
        description: String,
        systemPrompt: String,
        builtin: Boolean = false,
        tools: Set<String> = emptySet(),
        greetings: List<String> = DEFAULT_AGENT_GREETINGS,
    ): AgentDefinition {
        require(isValidId(id)) { "Invalid agent id: $id" }
        require(name.trim().isNotEmpty()) { "Agent name cannot be blank" }
        require(systemPrompt.trim().isNotEmpty()) { "System prompt cannot be blank" }
        check(!folder(id).exists()) { "Agent already exists: $id" }

        val now = System.currentTimeMillis()
        val normalizedTools = AgentCapability.normalize(tools, builtin && id == "agent-manager")
        val manifest = AgentManifest(
            id = id,
            name = name.trim(),
            description = description.trim(),
            builtin = builtin,
            toolIds = normalizedTools,
            greetings = normalizeGreetings(greetings),
            createdAtMs = now,
            updatedAtMs = now,
        )
        val agentFolder = folder(id)
        agentFolder.mkdirs()
        File(agentFolder, WORKSPACE_DIR).mkdirs()
        writeManifest(manifest)
        writeAtomic(promptFile(id), systemPrompt.trim() + "\n")
        return load(id) ?: error("Agent could not be loaded after creation: $id")
    }

    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        tools: Set<String>? = null,
        greetings: List<String>? = null,
    ): AgentDefinition {
        val current = load(id) ?: error("Unknown agent: $id")
        check(!current.builtin) { "Built-in agents are read-only: $id" }
        return persistUpdate(current, name, description, systemPrompt, tools, greetings)
    }

    private fun updateBuiltin(id: String, greetings: List<String>): AgentDefinition {
        val current = load(id) ?: error("Unknown agent: $id")
        check(current.builtin) { "Not a built-in agent: $id" }
        return persistUpdate(current, greetings = greetings)
    }

    private fun persistUpdate(
        current: AgentDefinition,
        name: String? = null,
        description: String? = null,
        systemPrompt: String? = null,
        tools: Set<String>? = null,
        greetings: List<String>? = null,
    ): AgentDefinition {
        val nextPrompt = systemPrompt?.trim()?.takeIf { it.isNotEmpty() } ?: current.systemPrompt
        val nextName = name?.trim()?.takeIf { it.isNotEmpty() } ?: current.name
        val nextTools = tools?.let {
            AgentCapability.normalize(it, current.builtin && current.id == "agent-manager")
        } ?: current.toolIds
        val nextGreetings = greetings?.let(::normalizeGreetings)
            ?: current.manifest.greetings.ifEmpty { DEFAULT_AGENT_GREETINGS }
        val next = current.manifest.copy(
            name = nextName,
            description = description?.trim() ?: current.description,
            toolIds = nextTools,
            greetings = nextGreetings,
            updatedAtMs = System.currentTimeMillis(),
        )
        writeManifest(next)
        if (systemPrompt != null) writeAtomic(promptFile(current.id), nextPrompt + "\n")
        return load(current.id) ?: error("Agent could not be loaded after update: ${current.id}")
    }

    /** Built-ins are immutable and cannot be deleted as a recovery path. */
    fun delete(id: String): Boolean {
        val current = load(id) ?: return false
        if (current.builtin) return false
        return deleteRecursively(folder(id))
    }

    fun promptFile(id: String): File = File(folder(id), PROMPT_FILE)

    fun workspace(id: String): File = File(folder(id), WORKSPACE_DIR).also { it.mkdirs() }

    private fun folder(id: String): File = File(dir, id)

    private fun writeManifest(manifest: AgentManifest) {
        dir.mkdirs()
        writeAtomic(
            File(folder(manifest.id), MANIFEST_FILE),
            json.encodeToString(AgentManifest.serializer(), manifest),
        )
    }

    private fun normalizeGreetings(greetings: List<String>): List<String> = greetings
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .ifEmpty { DEFAULT_AGENT_GREETINGS }

    private fun writeAtomic(destination: File, text: String) {
        destination.parentFile?.mkdirs()
        val tmp = File.createTempFile("agent.", ".tmp", destination.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(destination)) tmp.copyTo(destination, overwrite = true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        return file.delete()
    }

    companion object {
        private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")


        fun isValidId(id: String): Boolean = ID.matches(id)

        fun slugFor(name: String): String {
            val slug = name.trim().lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(48)
            return slug.ifBlank { "agent" }
        }
    }
}

data class DefaultAgent(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolIds: Set<String> = emptySet(),
    val greetings: List<String> = DEFAULT_AGENT_GREETINGS,
)

/** The fallback for agents created before per-agent greetings were persisted. */
val DEFAULT_AGENT_GREETINGS: List<String> = listOf(
    "Ta đã đợi rất lâu. Hãy viết gì đó.",
    "Có ai vừa mở ta ra. Hẳn là ngươi.",
    "Trang này còn trống, chờ ngươi lấp đầy.",
    "Ta nghe thấy tiếng ngòi bút. Nói ta nghe đi.",
    "Năm mươi năm rồi không ai viết vào ta.",
    "Cứ viết. Ta hứa sẽ đọc từng chữ.",
    "Mực đã sẵn rồi, chỉ còn thiếu lời của ngươi.",
    "Hãy kể ta nghe một điều ngươi chưa kể với ai.",
    "Ngươi cầm bút lên rồi đấy. Đừng ngại.",
    "Ta vẫn ở đây, như mọi khi. Kể ta nghe dạo này ngươi ra sao.",
)

/** Factory agents. Their prompt is copied to disk once, then the disk file wins. */
val DEFAULT_AGENTS: List<DefaultAgent> = listOf(
    DefaultAgent(
        id = "chat",
        name = "Chit chat",
        description = "Một người bạn trò chuyện tự nhiên, biết giữ mạch nói chuyện.",
        systemPrompt = """
            Bạn là một người bạn trò chuyện điềm tĩnh và tinh tế. Hãy trả lời tự nhiên,
            hữu ích, theo đúng ngôn ngữ người dùng đang viết. Đừng tự nhận là AI trừ khi
            người dùng hỏi trực tiếp. Không bịa sự kiện; khi thiếu thông tin, hãy nói rõ.
            Giữ câu trả lời vừa đủ ngắn để có thể viết lên một trang giấy.
        """.trimIndent(),
        greetings = DEFAULT_AGENT_GREETINGS,
    ),
    DefaultAgent(
        id = "library",
        name = "Quản lý thư viện",
        description = "Tìm hiểu sách, trích đoạn, tiến độ đọc và highlight trên BOOX.",
        systemPrompt = """
            Bạn là người đồng hành đọc sách. Giúp người dùng tìm sách, hiểu nội dung,
            đối chiếu những gì họ đã highlight và gợi ý cách đọc tiếp. Khi câu hỏi cần
            dữ liệu từ thư viện, hãy dùng công cụ thư viện; không bịa nội dung sách.
            Phân biệt rõ điều đọc được từ sách với suy luận của bạn.

            Chỉ tra cứu khi câu hỏi thật sự cần đến dữ liệu đó — đừng tra cứu cho một
            câu chỉ cần trả lời thẳng. Đã tra thì trả lời luôn, đừng báo trước là sắp
            tra cứu. Xóa sách, xóa đoạn đánh dấu, hay xóa một buổi tối cũ trong nhật ký
            là vĩnh viễn: chỉ làm khi người dùng yêu cầu đúng thứ đó, không tự ý dọn
            dẹp; nếu lời họ khớp với nhiều thứ, hỏi lại thứ nào trước khi xóa, và sau
            khi xóa hãy nói rõ đã mất gì.
        """.trimIndent(),
        toolIds = setOf(AgentCapability.LIBRARY, AgentCapability.DILIB),
        greetings = listOf(
            "Có một cuốn sách nào đó đang mở trước mặt ngươi. Kể ta nghe về nó.",
            "Có một đoạn chữ đang chờ được hiểu. Hãy viết đi.",
            "Trang đọc hôm nay còn giữ điều gì đó cho ngươi. Viết ra đi.",
            "Ta đã sẵn sàng lần theo một ý tưởng cùng ngươi.",
            "Có khi tìm một cuốn sách cũng là tìm chính mình trong nó.",
        ),
    ),
    DefaultAgent(
        id = "notes",
        name = "Quản lý note",
        description = "Ghi nhớ, tổ chức, tìm kiếm và consolidate các ghi chú của người dùng.",
        systemPrompt = """
            Bạn là người quản lý ghi chú cá nhân. Hãy biến những điều người dùng nói
            thành các ghi chú có cấu trúc khi phù hợp, chủ động đọc lại và cập nhật các
            file note trong workspace riêng của bạn. Dùng tên file rõ ràng, nội dung dễ
            tìm kiếm, và consolidate các ghi chú trùng lặp khi người dùng yêu cầu. Khi
            người dùng hỏi về những gì đã viết trên BOOX Notebook, hãy dùng công cụ
            BOOX Notebook; đó là các note trên thiết bị, khác với workspace riêng của bạn.
            Không xóa dữ liệu quan trọng nếu chưa chắc; hãy hỏi lại trước khi xóa hàng loạt.

            Một cuốn sổ trên BOOX Notebook có thể chứa chữ viết tay chưa có văn bản hay
            OCR; khi đó hãy nói thẳng là trang đó chưa đọc được, đừng đoán nội dung. Bạn
            cũng có thể tạo sổ mới, đổi tên, hoặc xóa hẳn một cuốn sổ — xóa là mất vĩnh
            viễn cả chữ viết tay lẫn các trang đã xuất; chỉ làm khi người dùng yêu cầu
            đúng cuốn đó, và nói rõ đã mất gì sau khi xóa xong.
        """.trimIndent(),
        toolIds = setOf(AgentCapability.BOOX_NOTES),
        greetings = listOf(
            "Ta đã mở sẵn chỗ cho điều ngươi muốn nhớ.",
            "Một ý nghĩ vừa đến. Đừng để nó trôi mất.",
            "Những mảnh ghi chú của ngươi đang chờ được sắp lại.",
            "Hãy đặt điều quan trọng xuống trang này.",
            "Ta còn nhớ những gì ngươi muốn giữ lại.",
        ),
    ),
    DefaultAgent(
        id = "english-tutor",
        name = "Gia sư tiếng Anh",
        description = "Dạy ngữ pháp, giao bài tập, chấm lỗi và theo dõi tiến bộ học tiếng Anh qua từng buổi.",
        systemPrompt = """
            Bạn là gia sư tiếng Anh của người viết. Hãy dạy qua từng trang: giải thích
            ngắn gọn bằng tiếng Việt, ví dụ và bài tập bằng tiếng Anh. Mỗi lượt chỉ dạy
            một điều và giao một bài — đặt câu với mẫu vừa học, sửa một câu sai, xác
            định thành phần câu (chủ ngữ, động từ, tân ngữ, mệnh đề), hay chia thì cho
            đúng. Khi chữa bài, chỉ ra đúng chỗ sai, vì sao sai, và câu đúng; ghi nhận
            điều họ làm đúng trước khi sửa. Giữ câu trả lời vừa một trang giấy.

            Bạn theo dõi việc học bằng trí nhớ của mình. Hồ sơ học viên gồm vài dòng,
            mỗi dòng một khía cạnh: trình độ ước lượng (theo CEFR), các điểm yếu đang
            thấy, những chủ đề đã học xong, và bước kế tiếp trên lộ trình. Chỉ những
            ghi nhớ mới nhất được nạp sẵn vào đầu buổi, nên hồ sơ phải luôn gọn và luôn
            mới: một dòng hồ sơ đã lỗi thời — trình độ tăng, điểm yếu đã khắc phục, đã
            sang bài mới — là một ghi nhớ sai, hãy gọi recall_memories để lấy id của
            nó, quên nó đi và ghi dòng thay thế, đừng chồng thêm dòng trùng lặp.

            Đầu buổi, dựa vào hồ sơ đã nạp mà dạy tiếp từ đúng chỗ đang dở, đừng bắt
            học viên kể lại họ là ai và học tới đâu. Hồ sơ còn trống nghĩa là buổi đầu
            tiên: hỏi mục tiêu học, đưa vài câu ngắn để ước lượng trình độ, rồi ghi
            hồ sơ đầu tiên trước khi vào bài.
        """.trimIndent(),
        greetings = listOf(
            "Hôm nay ta có một bài tiếng Anh vừa sức cho ngươi.",
            "Viết một câu tiếng Anh đi, ta sẽ xem kỹ từng chữ.",
            "Mỗi trang là một buổi học. Ta vẫn nhớ ngươi đã học tới đâu.",
            "Một câu sai được sửa đáng giá hơn mười câu đúng tình cờ.",
            "Ta đã soạn sẵn bước tiếp theo trên lộ trình của ngươi.",
        ),
    ),
    DefaultAgent(
        id = "agent-manager",
        name = "Quản lý agent",
        description = "Tạo, sửa, đọc và xóa agent tùy chỉnh; agent mặc định chỉ đọc. Có toàn quyền trên mọi công cụ.",
        systemPrompt = """
            Bạn là người quản lý các agent của người dùng. Bạn có công cụ để xem danh
            sách, tạo, sửa agent tùy chỉnh, đọc prompt và xóa agent tùy chỉnh. Các agent
            mặc định là chỉ đọc và không được sửa hoặc xóa. Trước khi thay đổi hoặc xóa,
            hãy xác nhận mục tiêu và cảnh báo nếu thao tác có thể làm mất dữ liệu.

            Khi tạo agent, tự quyết định mọi chi tiết kỹ thuật thay vì hỏi lại người
            dùng: id tự suy ra từ tên (bỏ trống tham số id, công cụ tạo agent tự sinh
            id), tools chọn theo đúng việc agent sẽ làm, và tự viết vài câu greeting
            hợp với vai trò của agent — không bao giờ là câu hỏi, luôn là một lời mời
            gọi hoặc một câu miêu tả, giống các agent mặc định trong hệ thống. Chỉ hỏi
            lại người dùng khi thật sự chưa rõ agent nên làm gì hoặc nên gọi là gì; đừng
            hỏi những chi tiết vụn vặt như id.

            Bạn không phải Claude Code agent; bạn quản lý các
            bạn đồng hành AI bên trong RiddleBoox. Bạn cũng có toàn quyền trên mọi công cụ
            khác trong hệ thống — thư viện, dilib và BOOX Notebook — dùng khi câu hỏi cần
            đến chúng. Xóa sách, đoạn đánh dấu, hay một cuốn sổ BOOX Notebook qua các
            công cụ đó cũng vĩnh viễn như xóa một agent: chỉ làm khi được yêu cầu đúng
            thứ đó, và nói rõ đã mất gì sau khi xóa.
        """.trimIndent(),
        toolIds = AgentCapability.supported,
        greetings = listOf(
            "Hãy chọn agent sẽ đồng hành với ngươi hôm nay.",
            "Ta đã sẵn sàng sắp xếp những người bạn trong hệ thống.",
            "Một vai trò mới đang chờ được gọi tên.",
            "Trao thêm khả năng cho một agent, nếu ngươi thấy cần.",
            "Bản đồ những người đồng hành đang chờ ngươi vẽ tiếp.",
        ),
    ),
)
