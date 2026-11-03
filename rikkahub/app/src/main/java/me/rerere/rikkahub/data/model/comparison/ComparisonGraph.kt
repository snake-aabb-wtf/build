package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.ensureActive
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** 某个会话走过某个节点：跳回时用 [messageNodeId] 定位到节点。 */
data class NodeOccurrence(
    val conversationId: Uuid,
    val conversationTitle: String,
    val messageId: Uuid,
    val messageNodeId: Uuid,
)

data class ComparisonNode(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val message: UIMessage,
    val occurrences: List<NodeOccurrence>,
    val childIds: List<String>,
    internal val fingerprint: String,
)

data class ComparisonGraph(
    val rootIds: List<String>,
    val nodes: Map<String, ComparisonNode>,
) {
    val nodeCount: Int get() = nodes.size
}

private class MutableNode(
    val id: String,
    val parentId: String?,
    val depth: Int,
    val message: UIMessage,
    val fingerprint: String,
    val occurrences: MutableList<NodeOccurrence> = mutableListOf(),
    val childIds: MutableList<String> = mutableListOf(),
) {
    fun freeze(): ComparisonNode = ComparisonNode(
        id = id,
        parentId = parentId,
        depth = depth,
        message = message,
        occurrences = occurrences.toList(),
        childIds = childIds.toList(),
        fingerprint = fingerprint,
    )
}

/**
 * 按「从根到该节点的路径逐字相同」合并多个会话，得到一棵前缀树。
 *
 * 只读取每个会话当前选中的消息；下标越界的节点跳过。每个节点最多一个父节点，分叉后不会回并。
 *
 * 消息指纹每次重算（纯字符串/JSON 规范化，微秒级）：context checkpoint 会原地改写内容而不换 id，
 * 任何按键缓存都会拿到过期指纹，算错合并结果。只有附件内容哈希由 [FileHasher] 自己按文件缓存。
 */
suspend fun buildComparisonGraph(
    conversations: List<Conversation>,
    fileHasher: FileHasher,
): ComparisonGraph {
    val nodes = LinkedHashMap<String, MutableNode>()
    val rootIds = mutableListOf<String>()
    var sinceCancelCheck = 0

    conversations.forEach { conversation ->
        var parentId: String? = null
        var depth = 0
        var siblings: MutableList<String> = rootIds

        for (messageNode in conversation.messageNodes) {
            // 几千条消息的会话也要能中途取消，别把主线程/后台线程锁死到跑完
            if (sinceCancelCheck++ % CANCEL_CHECK_INTERVAL == 0) {
                coroutineContext.ensureActive()
            }
            val message = messageNode.messages.getOrNull(messageNode.selectIndex) ?: continue
            val fingerprint = messageFingerprint(message, fileHasher)
            val occurrence = NodeOccurrence(
                conversationId = conversation.id,
                conversationTitle = conversation.title,
                messageId = message.id,
                messageNodeId = messageNode.id,
            )

            val existingId = siblings.firstOrNull { id -> nodes[id]?.fingerprint == fingerprint }
            val nodeId = if (existingId != null) {
                nodes.getValue(existingId).occurrences += occurrence
                existingId
            } else {
                val newId = newNodeId(nodes, parentId, fingerprint)
                nodes[newId] = MutableNode(
                    id = newId,
                    parentId = parentId,
                    depth = depth,
                    message = message,
                    fingerprint = fingerprint,
                    occurrences = mutableListOf(occurrence),
                )
                siblings += newId
                newId
            }

            parentId = nodeId
            depth = nodes.getValue(nodeId).depth + 1
            siblings = nodes.getValue(nodeId).childIds
        }
    }

    return ComparisonGraph(
        rootIds = rootIds.toList(),
        nodes = nodes.mapValues { (_, node) -> node.freeze() },
    )
}

private const val CANCEL_CHECK_INTERVAL = 64

/** id 由父 id + 内容指纹派生，同一棵树里稳定可复现；哈希前缀冲突时追加序号。 */
private fun newNodeId(
    nodes: Map<String, MutableNode>,
    parentId: String?,
    fingerprint: String,
): String {
    val base = "$parentId\u0000$fingerprint".sha256Hex().take(16)
    var candidate = base
    var suffix = 1
    while (nodes.containsKey(candidate)) {
        suffix += 1
        candidate = "$base-$suffix"
    }
    return candidate
}
