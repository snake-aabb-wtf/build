package me.rerere.rikkahub.data.model.comparison

import kotlin.uuid.Uuid

/**
 * 每条会话在树上结束的节点。
 *
 * 树上的叶子不等于会话终点：一条会话可能停在分叉前，而另一条继续往下走。按 (会话, 最大深度) 取节点，
 * 页面上给这些节点画终点标记。
 */
fun conversationEndpoints(graph: ComparisonGraph): Set<String> =
    conversationEndpointTitles(graph).keys

/**
 * 每个终点节点上有哪些会话结束，标题按会话 id 去重。
 *
 * 卡片徽标要说清「谁在此结束」；多条会话同时停在同一节点时，界面只显示总数并可展开名字。
 */
fun conversationEndpointTitles(graph: ComparisonGraph): Map<String, List<String>> {
    val deepest = HashMap<Uuid, Pair<Int, String>>()
    val titles = HashMap<Uuid, String>()
    graph.nodes.values.forEach { node ->
        node.occurrences.forEach { occurrence ->
            titles[occurrence.conversationId] = occurrence.conversationTitle
            val current = deepest[occurrence.conversationId]
            if (current == null || node.depth > current.first) {
                deepest[occurrence.conversationId] = node.depth to node.id
            }
        }
    }
    val result = LinkedHashMap<String, MutableList<String>>()
    // 先按出现顺序排，再按会话 id 稳定排序，避免同深度终点的顺序随图遍历变化
    deepest.entries
        .sortedBy { it.key.toString() }
        .forEach { (conversationId, deepestNode) ->
            result.getOrPut(deepestNode.second) { mutableListOf() } += titles[conversationId].orEmpty()
        }
    return result
}
