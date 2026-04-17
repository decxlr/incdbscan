# 增量DBSCAN聚类算法详细执行流程 (Java实现参考)

## 1. 算法概述

IncrementalDBSCAN 是 DBSCAN 的增量变体。给定一个已有的聚类结果，当数据点被插入或删除时，它高效地更新聚类，而非从头重新计算。其核心保证是：**每次更新后的聚类结果与对该时刻的全部数据集运行完整DBSCAN的结果完全一致**。

### 核心参数
- `eps` (double): 邻域半径，两点的距离 ≤ eps 则互为邻居
- `minPts` (int): 核心对象所需的最少邻居数（邻居数 ≥ minPts 则为核心对象）
- `metric` (string/函数): 距度量函数（默认欧氏距离）
- `p` (double): Minkowski距离参数（默认2，即欧氏距离）

### 核心概念
| 概念 | 定义 |
|------|------|
| **核心对象 (Core Object)** | 邻居数 ≥ minPts 的对象 |
| **边界对象 (Border Object)** | 邞居数 < minPts 但在某个核心对象邻域内的对象 |
| **噪声 (Noise)** | 邞居数 < minPts 且不在任何核心对象邻域内的对象，标签为 -1 |
| **邻居 (Neighbor)** | 与该对象距离 ≤ eps 的所有对象（包括自身） |
| **邻居数 (Neighbor Count)** | 邞居集合中所有对象的 `count` 值之和（支持重复点） |

---

## 2. 数据结构设计 (Java类对应)

### 2.1 `IncrementalDBSCAN` — 主类

```
class IncrementalDBSCAN {
    double eps;
    int minPts;
    String metric;
    double p;

    Objects objects;      // 对象集管理器
    Inserter inserter;    // 插入处理器
    Deleter deleter;      // 删除处理器
}
```

### 2.2 `Object` — 数据点对象

```
class Object {
    long id;              // 对象唯一ID（通过hash值计算）
    int nodeId;           // 在图中的节点索引
    int count;            // 相同坐标点的重复计数（初始为1）
    Set<Object> neighbors; // ε邻域内的对象集合（包含自身）
    int neighborCount;    // 邞居数 = neighbors中所有对象的count之和

    boolean isCore() {
        return neighborCount >= minPts;
    }
}
```

**重要细节**: `neighborCount` 不是邻居集合的大小，而是所有邻居的 `count` 之和。当存在重复点时，每个重复点会增加其所有邻居的 `neighborCount`。

### 2.3 `Objects` — 对象集管理器（继承 LabelHandler）

```
class Objects extends LabelHandler {
    Graph graph;                    // 无向图（rustworkx PyGraph，Java可用JGraphT或自实现）
    Map<Long, Integer> objectIdToNodeId;  // 对象ID → 图节点ID映射
    NeighborSearcher neighborSearcher;    // 邞居搜索器
    int minPts;
}
```

### 2.4 `LabelHandler` — 标签管理器

```
class LabelHandler {
    Map<Integer, Set<Object>> labelToObjects;  // 标签 → 对象集合
    Map<Object, Integer> objectToLabel;         // 对象 → 标签

    // 常量
    int CLUSTER_LABEL_UNCLASSIFIED = -2;  // 新插入对象的初始标签
    int CLUSTER_LABEL_NOISE = -1;         // 噪声标签
    int CLUSTER_LABEL_FIRST_CLUSTER = 0;  // 第一个有效聚类标签
}
```

### 2.5 `NeighborSearcher` — 邞居搜索器

```
class NeighborSearcher {
    double radius;
    String metric;
    double p;

    // 内部维护:
    // - 数据点数组 (values)
    // - 对象ID有序列表 (ids, 用SortedList保持有序)
    // - 空间索引树 (KD-Tree 或 Ball Tree，每次插入后重建)

    void insert(double[] new_value, long new_id);
    List<Long> queryNeighbors(double[] query_value);  // 返回距离 ≤ radius 的所有对象ID
    void delete(long id);
}
```

**Java实现建议**: 使用 Apache Commons Math 的 KDTree，或自己维护一个数组+每次插入后重建。对于低维数据用KDTree，高维数据用BallTree或暴力搜索。

### 2.6 `Graph` — 无向图

用于存储对象间的邻域关系。图中的节点是 `Object`，边表示两对象互为ε邻居。

**Java实现建议**: 使用 JGraphT 的 `SimpleGraph<Object, DefaultEdge>`，或自行维护邻接表。

---

## 3. 插入流程详解 (Insert)

### 3.1 入口方法

```java
void insert(double[][] X) {
    // 1. 输入验证（确保是float/double二维数组）
    // 2. 逐点插入
    for (double[] value : X) {
        inserter.insert(value);
    }
}
```

### 3.2 `Inserter.insert(double[] object_value)` — 完整流程

```
┌─────────────────────────────────────────────────────────┐
│                 单点插入完整流程                          │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Step 1: 将新点加入对象集                                │
│    objects.insert_object(object_value)                  │
│    ↓ 返回 object_inserted                               │
│                                                         │
│  Step 2: 分类核心邻居                                   │
│    _separate_core_neighbors_by_novelty(object_inserted) │
│    ↓ 得到 new_core_neighbors, old_core_neighbors        │
│                                                         │
│  Step 3: 判断是否有新核心对象                           │
│    if (new_core_neighbors 为空):                        │
│      → 进入简单路径（Step 3a）                          │
│    else:                                                │
│      → 进入复杂路径（Step 3b）                          │
│                                                         │
│  Step 3a: 简单路径（无新核心对象产生）                   │
│    if (old_core_neighbors 不为空):                      │
│      → 新对象标签 = max(old_core_neighbors的标签)       │
│      → 这就是"吸收(Absorption)"情况                    │
│    else:                                                │
│      → 新对象标签 = NOISE (-1)                          │
│      → 这就是"噪声(Noise)"情况                         │
│    objects.set_label(object_inserted, label)            │
│    → 返回，流程结束                                     │
│                                                         │
│  Step 3b: 复杂路径（有新核心对象产生）                   │
│    → 继续 Step 4 ~ Step 7                              │
│                                                         │
│  Step 4: 计算更新种子集                                 │
│    update_seeds = _get_update_seeds(new_core_neighbors) │
│                                                         │
│  Step 5: 在更新种子中找连通分量                         │
│    components = objects.get_connected_components(       │
│        update_seeds)                                    │
│                                                         │
│  Step 6: 处理每个连通分量                               │
│    for component in components:                         │
│      effective_labels = 获取component中有效标签         │
│      if (effective_labels 为空):                        │
│        → "创建(Creation)": 新建一个聚类标签              │
│        → 所有对象标记为新标签                           │
│      else:                                              │
│        → "吸收+合并(Absorption/Merge)":                 │
│        → 所有对象标记为 max(effective_labels)           │
│        → 将所有旧标签合并到 max标签                     │
│                                                         │
│  Step 7: 更新新核心对象周围的边界/噪声对象              │
│    _set_cluster_label_around_new_core_neighbors(        │
│        new_core_neighbors)                              │
│    → 每个新核心对象的所有邻居继承其标签                 │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3.3 Step 1 详解: `objects.insert_object(object_value)`

```
insert_object(object_value):
  1. 计算 object_id = hash(object_value)  // 使用xxhash或类似哈希

  2. 如果 object_id 已存在（重复点）:
     a. 获取已有对象 obj
     b. obj.count += 1
     c. 对 obj 的所有邻居 neighbor: neighbor.neighborCount += 1
     d. 返回 obj

  3. 如果 object_id 不存在（新点）:
     a. 创建新对象 new_object = new Object(object_id, minPts)
        // 初始值: count=1, neighbors={self}, neighborCount=0

     b. 将 new_object 加入图:
        - node_id = graph.add_node(new_object)
        - new_object.nodeId = node_id
        - objectIdToNodeId[object_id] = node_id

     c. 设置初始标签:
        - set_label_of_inserted_object(new_object)
        // 标签 = UNCLASSIFIED (-2)

     d. 将坐标加入邻居搜索器:
        - neighborSearcher.insert(object_value, object_id)

     e. 更新邻居关系:
        - _update_neighbors_during_insertion(new_object, object_value)
        // 见下方详解

     f. 返回 new_object
```

### 3.4 Step 1e 详解: `_update_neighbors_during_insertion`

```
_update_neighbors_during_insertion(object_inserted, new_value):

  1. 查询新点的ε邻居:
     neighbor_ids = neighborSearcher.query_neighbors(new_value)
     // 返回所有与新点距离 ≤ eps 的已有对象ID

  2. 遍历每个邻居 obj:
     a. obj.neighborCount += 1  // 新点成为obj的邻居

     b. if obj.id != object_inserted.id:  // 不是自己
        - object_inserted.neighborCount += obj.count  // 反向计数
        - obj.neighbors.add(object_inserted)          // 双向加入邻居集合
        - object_inserted.neighbors.add(obj)
        - graph.add_edge(object_inserted.nodeId, obj.nodeId)  // 图中加边

  // 注意: object_inserted.neighbors 初始化时已包含自身
  // neighborCount的计算: 每个邻居的count都贡献到object_inserted.neighborCount
  // 所以如果某个邻居的count=2(重复点), 它贡献2而不是1
```

### 3.5 Step 2 详解: `_separate_core_neighbors_by_novelty`

```
_separate_core_neighbors_by_novelty(object_inserted):
  // 将 object_inserted 的邻居分为"新核心"和"旧核心"

  new_cores = 空集合
  old_cores = 空集合

  for obj in object_inserted.neighbors:
    if obj.neighborCount == minPts:
      // 恰好达到minPts → 刚刚成为核心，是"新核心"
      new_cores.add(obj)
    elif obj.neighborCount > minPts:
      // 已经超过minPts → 之前就是核心，是"旧核心"
      old_cores.add(obj)

  // 特殊处理: 如果被插入对象自身是旧核心
  // (即 neighborCount > minPts 但它刚被插入)
  if object_inserted 在 old_cores 中:
    old_cores.remove(object_inserted)
    new_cores.add(object_inserted)
    // 被插入对象即使neighborCount>minPts也视为新核心

  return new_cores, old_cores
```

**关键理解**: `neighborCount == minPts` 意味着该对象**刚好**因为这次插入而达到核心标准，之前不是核心。`neighborCount > minPts` 意味着该对象**之前**就已经是核心了。

### 3.6 Step 4 详解: `_get_update_seeds`

```
_get_update_seeds(new_core_neighbors):
  // 更新种子 = 所有新核心对象的核心邻居集合

  seeds = 空集合

  for new_core in new_core_neighbors:
    core_neighbors = [obj for obj in new_core.neighbors
                      if obj.neighborCount >= minPts]  // 只取核心邻居
    seeds.update(core_neighbors)

  return seeds
  // seeds 包含所有新核心及其所有核心邻居
  // 这些是可能需要更新标签的核心对象
```

### 3.7 Step 5 详解: `get_connected_components_within_objects`

```
get_connected_components_within_objects(objects):
  // 在图中的子图上找连通分量

  if objects 只有1个对象:
    return [objects]  // 单个对象自成一组

  1. 取出所有对象的 nodeId 列表
  2. 从 graph 中提取子图 subgraph (只包含这些节点及其之间的边)
  3. 在 subgraph 上找连通分量:
     components_as_ids = connected_components(subgraph)
     // 使用 rustworkx 的 connected_components
     // Java中可用 JGraphT 的 ConnectivityInspector

  4. 将 nodeId 连通分量转换为 Object 集合:
     return [{subgraph[nodeId] for nodeId in component}
             for component in components_as_ids]
```

### 3.8 Step 6 详解: 处理连通分量

```
for component in connected_components_in_update_seeds:
  effective_labels = _get_effective_cluster_labels_of_objects(component)
  // effective_labels = component中标签不是UNCLASSIFIED(-2)或NOISE(-1)的标签集合

  if effective_labels 为空:
    // 【创建 Creation】
    // component中全是之前未分类或噪声的对象
    next_label = objects.get_next_cluster_label()
    // next_label = max(labelToObjects.keySet()) + 1
    objects.set_labels(component, next_label)
    // 将component中所有对象标记为新标签

  else:
    // 【吸收 Absorption / 合并 Merge】
    max_label = max(effective_labels)

    // 先将component内所有对象统一到max_label
    objects.set_labels(component, max_label)

    // 再将所有旧标签的聚类合并到max_label
    for label in effective_labels:
      objects.change_labels(label, max_label)
      // change_labels: 将label下的所有对象都改为max_label
      //                 从labelToObjects中删除label
      //                 合并到labelToObjects[max_label]
```

### 3.9 Step 7 详解: `_set_cluster_label_around_new_core_neighbors`

```
_set_cluster_label_around_new_core_neighbors(new_core_neighbors):
  // 新核心对象的所有邻居（包括边界和噪声）继承该核心的标签

  for new_core in new_core_neighbors:
    label = objects.get_label(new_core)
    objects.set_labels(new_core.neighbors, label)
    // 对 new_core 的每个邻居设置标签为 new_core 的标签
    // 这会影响边界对象和噪声对象
    // 也影响被插入对象自身（如果它是新核心的邻居）
```

---

## 4. 删除流程详解 (Delete)

### 4.1 入口方法

```java
void delete(double[][] X) {
    // 1. 输入验证
    // 2. 逐点删除
    for (int i = 0; i < X.length; i++) {
        Object obj = objects.get_object(X[i]);
        if (obj != null) {
            deleter.delete(obj);
        } else {
            // 发出警告: 该点不在对象集中
        }
    }
}
```

### 4.2 `Deleter.delete(Object object_to_delete)` — 完整流程

```
┌──────────────────────────────────────────────────────────────┐
│                   单点删除完整流程                            │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Step 1: 从对象集中删除该点                                  │
│    objects.delete_object(object_to_delete)                   │
│                                                              │
│  Step 2: 找出失去核心属性的对象                              │
│    ex_cores = _get_objects_that_lost_core_property(          │
│        object_deleted)                                       │
│                                                              │
│  Step 3: 计算更新种子集和前核心的非核心邻居                  │
│    update_seeds, non_core_neighbors_of_ex_cores =            │
│      _get_update_seeds_and_non_core_neighbors_of_ex_cores(  │
│          ex_cores, object_deleted)                            │
│                                                              │
│  Step 4: 检查是否需要处理分裂                                │
│    if (update_seeds 不为空):                                 │
│      update_seeds_by_cluster = 按聚类标签分组                │
│      for seeds in update_seeds_by_cluster.values():          │
│        components = _find_components_to_split_away(seeds)    │
│        for component in components:                          │
│          新标签 = objects.get_next_cluster_label()            │
│          objects.set_labels(component, 新标签)               │
│                                                              │
│  Step 5: 更新前核心的边界对象标签                            │
│    _set_each_border_object_labels_to_largest_around(         │
│        non_core_neighbors_of_ex_cores)                       │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 4.3 Step 1 详解: `objects.delete_object(obj)`

```
delete_object(obj):

  1. obj.count -= 1

  2. 判断是否完全移除:
     remove_from_data = (obj.count == 0)  // 没有重复点了

  3. 更新所有邻居的邻居数:
     for neighbor in obj.neighbors:
       neighbor.neighborCount -= 1
       if remove_from_data:
         if neighbor.id != obj.id:
           neighbor.neighbors.remove(obj)  // 从邻居集合中移除

  4. 如果完全移除 (remove_from_data):
     a. 从图中移除:
        graph.remove_node(obj.nodeId)
        objectIdToNodeId.remove(obj.id)

     b. 从邻居搜索器中移除:
        neighborSearcher.delete(obj.id)

     c. 从标签系统中移除:
        delete_label_of_deleted_object(obj)
        // 从 labelToObjects 和 objectToLabel 中删除
```

### 4.4 Step 2 详解: `_get_objects_that_lost_core_property`

```
_get_objects_that_lost_core_property(object_deleted):
  // 找出因为删除而失去核心属性的对象
  // 即之前 neighborCount >= minPts，现在 neighborCount == minPts - 1

  threshold = minPts - 1

  for obj in object_deleted.neighbors:
    if obj.neighborCount == threshold:
      yield obj
      // 注意: 这些对象仍在对象集中（只是不再是核心了）

  // 如果被删除对象本身是核心:
  if object_deleted.isCore():
    yield object_deleted
    // 被删除对象也视为失去核心属性的对象
```

### 4.5 Step 3 详解: `_get_update_seeds_and_non_core_neighbors_of_ex_cores`

```
_get_update_seeds_and_non_core_neighbors_of_ex_cores(ex_cores, object_deleted):

  update_seeds = 空集合
  non_core_neighbors_of_ex_cores = 空集合

  for ex_core in ex_cores:
    for neighbor in ex_core.neighbors:
      if neighbor.isCore():
        update_seeds.add(neighbor)        // 核心邻居 → 更新种子
      else:
        non_core_neighbors_of_ex_cores.add(neighbor)  // 非核心邻居

  // 如果被删除对象已从数据中完全移除:
  if object_deleted.count == 0:
    update_seeds.remove(object_deleted)
    non_core_neighbors_of_ex_cores.remove(object_deleted)

  return update_seeds, non_core_neighbors_of_ex_cores
```

**关键理解**:
- `update_seeds`: 前核心对象周围**仍然是核心**的邻居 → 这些可能构成分裂后的独立连通分量
- `non_core_neighbors_of_ex_cores`: 前核心对象周围**不再是核心**的邻居（边界对象）→ 这些需要重新分配标签

### 4.6 Step 4 详解: 分裂检测与处理

```
// Step 4a: 按聚类标签分组
update_seeds_by_cluster = _group_objects_by_cluster(update_seeds)
// 按 get_label(obj) 分组

// Step 4b: 对每组种子检测分裂
for seeds in update_seeds_by_cluster.values():
  components = _find_components_to_split_away(seeds)
  for component in components:
    new_label = objects.get_next_cluster_label()
    objects.set_labels(component, new_label)
```

### 4.7 `_find_components_to_split_away` 详解 — BFS分裂检测

```
_find_components_to_split_away(seed_objects):

  // 快速返回: 如果种子太少或完全连通则无需分裂

  if seed_objects.size() == 1:
    return []  // 单个种子不会分裂

  if _objects_are_neighbors_of_each_other(seed_objects):
    return []  // 所有种子互为邻居 → 完全连通，不会分裂

  // 使用多源BFS找分裂分量
  finder = new BFSComponentFinder(objects.graph)
  seed_node_ids = [obj.nodeId for obj in seed_objects]
  components = finder.find_components(seed_node_ids)
  return components
```

### 4.8 BFS分裂检测算法详解 — `BFSComponentFinder`

这是删除流程中最复杂的部分，用于检测一个聚类是否因为删除而分裂为多个独立子聚类。

```
find_components(seeds):

  ┌──────────────────────────────────────────────────────────┐
  │              BFS分裂检测算法                              │
  ├──────────────────────────────────────────────────────────┤
  │                                                          │
  │  Step P1: 预处理 — 创建虚拟源节点                       │
  │    origin_object = Object("ORIGIN", 0)                  │
  │    origin_node_id = graph.add_node(origin_object)        │
  │    for each seed_node_id in seeds:                       │
  │      graph.add_edge(origin_node_id, seed_node_id)        │
  │    // 虚拟节点连接所有种子，实现多源BFS                  │
  │                                                          │
  │  Step P2: 执行BFS遍历                                   │
  │    bfs_search(graph, [origin_node_id], visitor=this)     │
  │    // visitor 即 BFSComponentFinder 自身                 │
  │                                                          │
  │  Step P3: 后处理 — 清理虚拟节点                         │
  │    graph.remove_node(origin_node_id)                     │
  │    删除 seed_to_component 中 origin 的记录               │
  │    // 丢弃未被完全遍历的分量（即保留的主分量）           │
  │    remaining_node = queue.poll()                         │
  │    remaining_seed = node_to_seed[remaining_node]         │
  │    删除 seed_to_component[remaining_seed]                │
  │                                                          │
  │    return seed_to_component.values()                     │
  │    // 返回的是需要分裂出去的分量                         │
  │                                                          │
  └──────────────────────────────────────────────────────────┘

  BFS访问事件处理:

  discover_vertex(vertex_node_id):
    // 首次发现节点时:
    if vertex_node_id 不在 node_to_seed 中:
      // 自身作为种子（处理孤立节点）
      node_to_seed[vertex_node_id] = vertex_node_id
      seed_to_component[vertex_node_id].add(graph[vertex_node_id])

    if graph[vertex_node_id].isCore():
      queue.append(vertex_node_id)  // 核心对象继续遍历
    else:
      raise PruneSearch  // 非核心对象 → 剪枝，不继续遍历
      // 关键: 只有核心对象之间的连通才算密度连通

  tree_edge(edge):  // BFS树的边（首次到达新节点）
    source, target, _ = edge
    source_is_origin = (source == origin_node_id)

    if source_is_origin:
      target_seed = target  // 从origin出发 → 目标就是自己的种子
    else:
      target_seed = node_to_seed[source]  // 继承源节点的种子

    node_to_seed[target] = target_seed
    seed_to_component[target_seed].add(graph[target])

  gray_target_edge(edge):  // 非树边（已访问节点的新边）
    source, target, _ = edge
    source_seed = node_to_seed[source]
    target_seed = node_to_seed[target]

    if source_seed != target_seed AND target.isCore():
      // 不同种子的分量通过核心对象相遇 → 合并分量
      // 将 target_seed 的分量合并到 source_seed
      objects_to_merge = seed_to_component[target_seed]
      for obj in objects_to_merge:
        node_to_seed[obj.nodeId] = source_seed
      seed_to_component[source_seed].update(objects_to_merge)
      seed_to_component.remove(target_seed)

  finish_vertex(vertex):
    queue.poll()  // 从队列移除已完成的节点

    if queue中所有节点的种子相同:
      raise StopSearch  // 只剩一个分量 → 所有其他分量已完全遍历
      // BFS提前终止
```

**核心思想**:
- 从多个种子出发同时BFS，每个节点继承其来源的种子标签
- 当两个不同种子的分量通过核心对象相遇时，它们合并（说明密度连通，不会分裂）
- 只有核心对象才能"传递"连通性（非核心对象处剪枝）
- 当队列中只剩一种种子时，说明所有需要分裂出去的分量都已找完

### 4.9 Step 5 详解: `_set_each_border_object_labels_to_largest_around`

```
_set_each_border_object_labels_to_largest_around(objects_to_set):
  // 为前核心的非核心邻居重新分配标签

  cluster_updates = 新Map

  for obj in objects_to_set:
    labels = _get_cluster_labels_in_neighborhood(obj)
    // labels = obj.neighbors中所有核心邻居的标签集合

    if labels 为空:
      labels.add(CLUSTER_LABEL_NOISE)  // 没有核心邻居 → 变为噪声

    cluster_updates[obj] = max(labels)
    // 选择最大的标签（"最新"的聚类）

  // 执行更新
  for obj, new_label in cluster_updates:
    objects.set_label(obj, new_label)
```

**关键**: 边界对象的标签取决于其核心邻居的标签。如果一个边界对象有多个核心邻居属于不同聚类，它选择标签值最大的那个聚类。如果没有核心邻居，它变为噪声。

---

## 5. 标签管理详解 (`LabelHandler`)

### 5.1 标签值体系

```
CLUSTER_LABEL_UNCLASSIFIED = -2  // 新插入对象的初始标签
CLUSTER_LABEL_NOISE = -1         // 噪声标签
有效聚类标签从 0 开始递增       // 0, 1, 2, 3, ...
```

### 5.2 核心方法

```
set_label(obj, label):
  // 更新对象的标签
  previous_label = objectToLabel[obj]
  if previous_label == label: return  // 无变化

  labelToObjects[previous_label].remove(obj)  // 从旧标签集合移除
  labelToObjects[label].add(obj)              // 加入新标签集合
  objectToLabel[obj] = label                  // 更新映射

set_label_of_inserted_object(obj):
  // 新插入对象初始标签为 UNCLASSIFIED
  objectToLabel[obj] = UNCLASSIFIED
  labelToObjects[UNCLASSIFIED].add(obj)

set_labels(objects, label):
  // 对一组对象批量设置标签
  for obj in objects:
    set_label(obj, label)

get_next_cluster_label():
  // 下一个可用聚类标签 = max(labelToObjects.keySet()) + 1
  // 因为标签只增不减（合并时旧标签被删除），所以这保证唯一性

change_labels(change_from, change_to):
  // 将一个标签下的所有对象改为另一个标签（聚类合并）
  if change_from == change_to: return

  affected_objects = labelToObjects.remove(change_from)  // 取出并删除旧标签
  labelToObjects[change_to].update(affected_objects)     // 加入新标签集合

  for obj in affected_objects:
    objectToLabel[obj] = change_to                       // 更新映射
```

---

## 6. 邻居搜索详解 (`NeighborSearcher`)

### 6.1 搜索策略选择

```
if metric 是 Minkowski 类:
  if 维度 ≤ 15 AND p ≥ 1:
    → 使用 cKDTree (Java中: KDTree)  // 低维高效
  else:
    → 使用 sklearn BallTree (Java中: BallTree或暴力搜索)  // 高维
else:
  → 使用 sklearn NearestNeighbors (Java中: 按metric实现)  // 自定义距离
```

### 6.2 核心数据结构

```
class BaseNeighborSearcher {
    double[][] values;      // 数据点数组，按id排序
    SortedList<Long> ids;   // 有序的对象ID列表

    void insert(double[] new_value, long new_id):
      1. ids.add(new_id)                         // 有序插入
      2. position = ids.index(new_id)            // 找到位置
      3. values = insert_into_array(new_value, position)  // 在对应位置插入
      4. _rebuild()                              // 重建空间索引树

    List<Long> query_neighbors(double[] query_value):
      1. neighbor_indices = _get_neighbor_indices(query_value)  // 树查询
      2. return [ids[ix] for ix in neighbor_indices]           // 转换为对象ID

    void delete(long id_):
      1. position = ids.index(id_)
      2. ids.remove(position)
      3. values = delete_from_array(position)
      // 注意: 不立即重建树，下次查询时重建或在insert时重建
}
```

**Java实现注意**: 每次插入后都重建空间索引树。这在数据量大时可能较慢，但保证了查询的正确性。可以考虑延迟重建策略。

---

## 7. 图数据结构详解

### 7.1 图的作用

图存储对象间的ε邻域关系:
- **节点**: 每个 `Object` 是图中的一个节点
- **边**: 如果两个对象互为ε邻居（距离 ≤ eps），则有一条边
- **边不代表权重**: 所有边权重为 null/None

### 7.2 关键图操作

```
add_node(object):  // 添加节点，返回 node_id
add_edge(node_id1, node_id2):  // 添加边
remove_node(node_id):  // 移除节点及其所有边
subgraph(node_ids):  // 提取子图（只包含指定节点及其之间的边）
connected_components(subgraph):  // 找子图中的连通分量
neighbors(node_id):  // 获取节点的邻居节点列表
```

---

## 8. 完整执行示例

### 示例1: 创建新聚类 (Creation)

场景: `eps=1, minPts=3`

```
1. 插入点 A=(0,0) → neighborCount=1, 标签=NOISE
2. 插入点 B=(0.5,0) → A和B互为邻居
   - A.neighborCount=2, B.neighborCount=2
   - B标签=NOISE (没有新核心)
3. 插入点 C=(0,0.5) → A,B,C互为邻居
   - A.neighborCount=3 ≥ minPts → A成为核心! (新核心)
   - B.neighborCount=3 ≥ minPts → B成为核心! (新核心)
   - C.neighborCount=3 ≥ minPts → C成为核心! (新核心)
   - new_core_neighbors = {A, B, C}
   - update_seeds = {A, B, C} (它们互为核心邻居)
   - 连通分量: {A,B,C} (全连通)
   - effective_labels = 空 (全是NOISE/UNCLASSIFIED)
   - → 创建新聚类, 标签=0
   - A,B,C标签都设为0
   - 邻居继承: A.neighbors={A,B,C}标签全为0 ✓
```

### 示例2: 吸收 (Absorption)

场景: 已有聚类标签0，插入一个边界点

```
已有: 聚类0包含若干核心对象
插入点 D，距离某个核心对象 ≤ eps

1. D.neighborCount < minPts → D不是核心
2. D有核心邻居(属于聚类0) → old_core_neighbors不为空
3. 没有新核心 → 进入简单路径
4. D标签 = max(old_core_neighbors标签) = 0
5. D被"吸收"到聚类0
```

### 示例3: 合并 (Merge)

场景: 两个独立聚类，新插入点将它们连接

```
已有: 聚类0(左) 和 聚类1(右)，互不相邻
插入点 E 在两聚类之间，距离两边的核心对象都 ≤ eps

1. E的插入使左边某些对象达到minPts → new_cores包含左边对象
2. E的插入使右边某些对象也达到minPts → new_cores包含右边对象
3. E自身也可能成为核心 → new_cores包含E
4. update_seeds包含左、右两边的所有核心对象+E
5. 连通分量: 所有种子构成一个连通分量（通过E连接）
6. effective_labels = {0, 1}  // 来自两个聚类
7. → 合并: 所有对象标签改为 max(0,1) = 1
8. 聚类0合并到聚类1, labelToObjects中删除标签0
```

### 示例4: 分裂 (Split) — 删除场景

场景: 一个聚类通过"桥接点"连接，删除桥接点导致分裂

```
已有: 聚类1，包含左组、桥接点P、右组
P是唯一连接左右两组的对象

删除P:
1. P从对象集移除
2. P的邻居neighborCount -= 1
3. ex_cores: P的邻居中neighborCount降到minPts-1的对象 + P自身(如果P是核心)
4. update_seeds: ex_cores周围仍是核心的邻居
   → 左组的核心对象 + 右组的核心对象
5. 两组核心对象不互为邻居 → 需要分裂检测
6. BFS从左右两组种子出发:
   - 左组种子遍历到左组所有核心
   - 右组种子遍历到右组所有核心
   - 两组不会相遇(没有核心桥接)
   - BFS终止时，两组分属不同分量
7. 右组(较小分量)获得新标签2
8. 左组保留标签1
```

---

## 9. Java实现要点总结

### 9.1 必需的数据结构

| 数据结构 | Python实现 | Java建议 |
|----------|-----------|----------|
| 无向图 | rustworkx.PyGraph | JGraphT SimpleGraph 或自定义邻接表 |
| 空间索引 | scipy.cKDTree / sklearn.BallTree | Apache Commons Math KDTree 或自实现 |
| 有序ID列表 | sortedcontainers.SortedList | TreeMap 或自定义平衡树 |
| 哈希函数 | xxhash.xxh64 | Guava Hashing.sha256 或 Long.hash |
| 标签映射 | defaultdict(set) + dict | HashMap<Integer, Set<Object>> + HashMap<Object, Integer> |
| 对象邻居集 | Python set | HashSet<Object> |
| BFS遍历 | rustworkx.bfs_search | JGraphT BreadthFirstIterator 或自实现 |

### 9.2 伪代码总结 — 插入

```java
void insertPoint(double[] value) {
    // 1. 加入对象集，建立邻居关系
    Object obj = objects.insertObject(value);

    // 2. 分类核心邻居
    Set<Object> newCores = new HashSet<>();
    Set<Object> oldCores = new HashSet<>();
    for (Object neighbor : obj.neighbors) {
        if (neighbor.neighborCount == minPts) newCores.add(neighbor);
        else if (neighbor.neighborCount > minPts) oldCores.add(neighbor);
    }
    if (oldCores.contains(obj)) {
        oldCores.remove(obj);
        newCores.add(obj);
    }

    // 3. 无新核心 → 简单路径
    if (newCores.isEmpty()) {
        if (!oldCores.isEmpty()) {
            int label = maxLabel(oldCores);
            objects.setLabel(obj, label);
        } else {
            objects.setLabel(obj, NOISE);
        }
        return;
    }

    // 4. 有新核心 → 计算更新种子
    Set<Object> seeds = new HashSet<>();
    for (Object newCore : newCores) {
        for (Object n : newCore.neighbors) {
            if (n.neighborCount >= minPts) seeds.add(n);
        }
    }

    // 5. 在种子中找连通分量
    List<Set<Object>> components = objects.findConnectedComponents(seeds);

    // 6. 处理每个分量
    for (Set<Object> component : components) {
        Set<Integer> effectiveLabels = getEffectiveLabels(component);
        if (effectiveLabels.isEmpty()) {
            // Creation: 新建聚类
            int newLabel = objects.getNextClusterLabel();
            objects.setLabels(component, newLabel);
        } else {
            // Absorption/Merge: 合并到最大标签
            int maxLabel = Collections.max(effectiveLabels);
            objects.setLabels(component, maxLabel);
            for (int label : effectiveLabels) {
                objects.changeLabels(label, maxLabel);
            }
        }
    }

    // 7. 新核心的邻居继承标签
    for (Object newCore : newCores) {
        int label = objects.getLabel(newCore);
        objects.setLabels(newCore.neighbors, label);
    }
}
```

### 9.3 伪代码总结 — 删除

```java
void deletePoint(Object objToDelete) {
    // 1. 从对象集移除
    objects.deleteObject(objToDelete);

    // 2. 找出失去核心属性的对象
    List<Object> exCores = new ArrayList<>();
    int threshold = minPts - 1;
    for (Object neighbor : objToDelete.neighbors) {
        if (neighbor.neighborCount == threshold) exCores.add(neighbor);
    }
    if (objToDelete.isCore()) exCores.add(objToDelete);

    // 3. 计算更新种子和前核心的非核心邻居
    Set<Object> updateSeeds = new HashSet<>();
    Set<Object> nonCoreNeighbors = new HashSet<>();
    for (Object exCore : exCores) {
        for (Object neighbor : exCore.neighbors) {
            if (neighbor.isCore()) updateSeeds.add(neighbor);
            else nonCoreNeighbors.add(neighbor);
        }
    }
    if (objToDelete.count == 0) {
        updateSeeds.remove(objToDelete);
        nonCoreNeighbors.remove(objToDelete);
    }

    // 4. 分裂检测
    if (!updateSeeds.isEmpty()) {
        Map<Integer, List<Object>> byCluster = groupByCluster(updateSeeds);
        for (List<Object> seeds : byCluster.values()) {
            List<Set<Object>> components = findComponentsToSplitAway(seeds);
            for (Set<Object> component : components) {
                int newLabel = objects.getNextClusterLabel();
                objects.setLabels(component, newLabel);
            }
        }
    }

    // 5. 更新边界对象标签
    for (Object obj : nonCoreNeighbors) {
        Set<Integer> labels = getCoreNeighborLabels(obj);
        if (labels.isEmpty()) labels.add(NOISE);
        objects.setLabel(obj, Collections.max(labels));
    }
}
```

### 9.4 关键注意事项

1. **邻居数计算**: `neighborCount` 是所有邻居的 `count` 值之和，不是邻居集合大小。重复点会增加邻居数。
2. **标签选择**: 始终选择最大标签值（`max`），这保证了标签单调递增，避免冲突。
3. **密度连通**: 只有核心对象才能传递连通性。非核心对象在BFS中剪枝。
4. **分裂vs保留**: BFS分裂检测返回的是**需要分裂出去的分量**，保留主分量不变。
5. **空间索引重建**: 每次插入后重建KDTree/BallTree，删除后也更新数据但可能延迟重建。
6. **图的作用**: 图只存储邻域关系（边），不存储距离。所有边权重为空。
7. **重复点处理**: 相同坐标的点用 `count` 字段计数，不重复创建对象。删除时 `count` 递减，到0才真正移除。
8. **neighbors包含自身**: 每个对象的 `neighbors` 集合包含自己，这符合DBSCAN的定义（距离为0 ≤ eps）。