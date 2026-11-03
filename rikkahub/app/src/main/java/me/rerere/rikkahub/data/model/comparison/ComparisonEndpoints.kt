package me.rerere.rikkahub.data.model.comparison

import kotlin.uuid.Uuid

/**
 * 每条会话在树上结束的节点。
 *
 * 树上的叶子不等于会话终点：一条会话可能停在分叉前，而另一条继续往下走。按 (会话, 最大深度) 取节点，
 * 页面上给这些节点画终点标记。
 */
fun conversationEndpoints(graph: ComparisonGraph): Set<String> {
    val deepest = HashMap<Uuid, Pair<Int, String>>()
    graph.nodes.values.forEach { node ->
        node.occurrences.forEach { occurrence ->
            val current = deepest[occurrence.conversationId]
            if (current == null || node.depth > current.first) {
                deepest[occurrence.conversationId] = node.depth to node.id
            }
        }
    }
    return deepest.values.mapTo(HashSet()) { (_, id) -> id }
}
