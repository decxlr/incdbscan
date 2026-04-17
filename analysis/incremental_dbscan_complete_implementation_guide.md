# 增量DBSCAN聚类算法完整实现详解

## 目录
- [1. 算法概述](#1-算法概述)
- [2. 核心数据结构](#2-核心数据结构)
- [3. 插入流程详解](#3-插入流程详解)
- [4. 删除流程详解](#4-删除流程详解)
- [5. 关键子算法](#5-关键子算法)
- [6. 实现要点总结](#6-实现要点总结)
- [7. Java实现建议](#7-java实现建议)

---

## 1. 算法概述

### 1.1 什么是增量DBSCAN？

IncrementalDBSCAN 是经典 DBSCAN 聚类算法的增量版本。它的核心思想是：**当数据集发生增删变化时，利用已有的聚类结果进行局部更新，而不是重新对整个数据集运行DBSCAN**。

**关键保证**：每次更新后的聚类结果与对该时刻的完整数据集运行标准DBSCAN的结果完全一致。

### 1.2 算法优势

| 对比维度 | 传统DBSCAN | 增量DBSCAN |
|---------|-----------|-----------|
| 数据变化时 | 重新聚类全部数据 | 仅更新受影响区域 |
| 时间复杂度 | O(n log n) ~ O(n²) | 通常远小于O(n) |
| 适用场景 | 静态数据集 | 流式数据、频繁更新的数据集 |
| 空间复杂度 | O(n) | O(n) + 图结构开销 |

### 1.3 核心参数

```python
eps = 1.0      # 邻域半径：两点距离 ≤ eps 则互为邻居
min_pts = 5    # 最小邻居数：neighbor_count ≥ min_pts 为核心对象
metric = 'minkowski'  # 距离度量（默认欧氏距离）
p = 2          # Minkowski距离参数（p=2即欧氏距离）
```

### 1.4 基本概念定义

| 概念 | 定义 | 判定条件 |
|------|------|---------|
| **核心对象 (Core Object)** | 具有足够多邻居的对象 | `neighbor_count >= min_pts` |
| **边界对象 (Border Object)** | 在某个核心对象邻域内但自身不是核心 | `neighbor_count < min_pts` 且存在核心邻居 |
| **噪声 (Noise)** | 既不是核心也不在任何核心邻域内 | `neighbor_count < min_pts` 且无核心邻居，标签=-1 |
| **邻居 (Neighbor)** | 与该对象距离 ≤ eps 的所有对象（包含自身） | `distance(obj1, obj2) <= eps` |
| **邻居数 (Neighbor Count)** | 邻居集合中所有对象的count值之和 | `sum(neighbor.count for neighbor in neighbors)` |

**重要说明**：`neighbor_count` 不是邻居集合的大小，而是所有邻居的 `count` 值之和。这支持重复点的处理——相同坐标的点通过 `count` 字段计数，每个重复点会贡献其count值到所有邻居的 `neighbor_count`。

---

## 2. 核心数据结构

### 2.1 整体架构

```
IncrementalDBSCAN (主类)
├── Objects (对象管理器，继承LabelHandler)
│   ├── graph: PyGraph (无向图，存储邻域关系)
│   ├── _object_id_to_node_id: Dict[ObjectId, NodeId]
│   ├── neighbor_searcher: NeighborSearcher (空间索引)
│   └── min_pts: int
├── Inserter (插入处理器)
└── Deleter (删除处理器)
```

### 2.2 Object — 数据点对象

```python
class Object:
    id: ObjectId              # 对象唯一ID（通过hash计算）
    node_id: NodeId           # 在图中的节点索引
    count: int                # 相同坐标点的重复计数（初始为1）
    neighbors: Set[Object]    # ε邻域内的对象集合（包含自身）
    neighbor_count: int       # 邻居数 = Σ(neighbor.count)
    min_pts: int              # 核心判定阈值
    
    @property
    def is_core(self):
        return self.neighbor_count >= self.min_pts
```

**关键字段说明**：
- `id`: 使用xxhash对坐标数组进行哈希，相同坐标产生相同ID
- `count`: 处理重复点，相同坐标只创建一个Object，count递增
- `neighbors`: 包含自身（因为距离为0 ≤ eps），用于快速访问邻域
- `neighbor_count`: 动态维护，插入/删除时更新，避免重复计算

### 2.3 Objects — 对象集管理器

```python
class Objects(LabelHandler):
    graph: PyGraph                    # rustworkx无向图
    _object_id_to_node_id: Dict       # 对象ID → 图节点ID映射
    neighbor_searcher: NeighborSearcher  # 空间索引搜索器
    min_pts: int
    
    # 核心方法
    insert_object(value) -> Object    # 插入新对象
    delete_object(obj)                # 删除对象
    get_object(value) -> Object       # 查询对象
    get_connected_components_within_objects(objects) -> List[Set[Object]]
```

**图的作用**：
- 节点：每个Object是一个节点
- 边：如果两个对象互为ε邻居，则有一条边（权重为None）
- 用途：快速查找连通分量，支持BFS遍历

### 2.4 LabelHandler — 标签管理器

```python
class LabelHandler:
    _label_to_objects: Dict[int, Set[Object]]  # 标签 → 对象集合
    _object_to_label: Dict[Object, int]         # 对象 → 标签
    
    # 标签常量
    CLUSTER_LABEL_UNCLASSIFIED = -2  # 新插入对象的初始标签
    CLUSTER_LABEL_NOISE = -1         # 噪声标签
    CLUSTER_LABEL_FIRST_CLUSTER = 0  # 第一个有效聚类标签从0开始
```

**标签体系**：
- `-2`: 未分类（新插入对象初始状态）
- `-1`: 噪声
- `0, 1, 2, ...`: 有效聚类标签（单调递增）

**关键方法**：
```python
set_label(obj, label)                  # 设置单个对象标签
set_labels(objects, label)             # 批量设置标签
get_next_cluster_label() -> int        # 获取下一个可用标签 = max(labels) + 1
change_labels(change_from, change_to)  # 将一个标签下的所有对象改为另一个标签（合并聚类）
```

### 2.5 NeighborSearcher — 邻居搜索器

```python
class NeighborSearcher:
    _radius: float                     # eps
    _metric: str                       # 距离度量
    _effective_searcher: BaseNeighborSearcher  # 实际使用的搜索器
    
    insert(new_value, new_id)          # 插入点到空间索引
    query_neighbors(query_value)       # 查询ε邻居
    delete(id_)                        # 从空间索引删除
```

**自适应策略**：
```python
if metric 是 Minkowski 类:
    if 维度 ≤ 15 AND p ≥ 1:
        → 使用 cKDTree (低维高效)
    else:
        → 使用 sklearn NearestNeighbors (高维或自定义p)
else:
    → 使用 sklearn NearestNeighbors (自定义距离度量)
```

**内部数据结构**：
```python
class _BaseNeighborSearcher:
    values: np.ndarray     # 数据点数组，按id有序排列
    ids: SortedList        # 有序的对象ID列表
    # 空间索引树（cKDTree 或 sklearn模型）
```

**注意**：每次插入后都会重建空间索引树（`_rebuild()`），这保证了查询正确性但可能影响性能。

### 2.6 BFSComponentFinder — BFS分裂检测器

```python
class BFSComponentFinder(BFSVisitor):
    _graph: PyGraph
    _seed_to_component: Dict[NodeId, Set[Object]]  # 种子 → 分量对象集
    _node_to_seed: Dict[NodeId, NodeId]            # 节点 → 所属种子
    _queue: deque                                  # BFS队列
    _origin_node_id: NodeId                        # 虚拟源节点
    
    find_components(seeds) -> List[Set[Object]]    # 找需要分裂的分量
```

**作用**：在删除操作中检测一个聚类是否分裂为多个独立子聚类。

---

## 3. 插入流程详解

### 3.1 入口方法

```python
def insert(X):
    """插入多个数据点"""
    X = input_check(X)  # 输入验证，转为numpy数组
    for value in X:
        self._inserter.insert(value)  # 逐点插入
```

### 3.2 单点插入完整流程

```
┌─────────────────────────────────────────────────────────────┐
│                   单点插入完整流程                            │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Step 1: 将新点加入对象集                                    │
│    object_inserted = objects.insert_object(object_value)   │
│    ↓                                                        │
│    ├─ 计算 object_id = hash(object_value)                  │
│    ├─ 如果 object_id 已存在（重复点）:                      │
│    │   ├─ obj.count += 1                                   │
│    │   ├─ 对所有邻居: neighbor.neighbor_count += 1         │
│    │   └─ 返回 obj                                         │
│    └─ 如果 object_id 不存在（新点）:                        │
│        ├─ 创建新对象 new_object                             │
│        ├─ 加入图: node_id = graph.add_node(new_object)     │
│        ├─ 设置初始标签: UNCLASSIFIED (-2)                  │
│        ├─ 加入空间索引: neighbor_searcher.insert(...)      │
│        ├─ 更新邻居关系: _update_neighbors_during_insertion │
│        └─ 返回 new_object                                  │
│                                                             │
│  Step 2: 分类核心邻居                                        │
│    new_cores, old_cores =                                  │
│      _separate_core_neighbors_by_novelty(object_inserted)  │
│    ↓                                                        │
│    ├─ 遍历 object_inserted.neighbors                       │
│    ├─ if neighbor.neighbor_count == min_pts:               │
│    │   → 加入 new_cores (刚达到核心标准)                   │
│    ├─ elif neighbor.neighbor_count > min_pts:              │
│    │   → 加入 old_cores (之前就是核心)                     │
│    └─ 特殊处理: 如果 object_inserted 自身在 old_cores 中:  │
│        → 移到 new_cores (被插入对象视为新核心)              │
│                                                             │
│  Step 3: 判断是否有新核心对象                                │
│    if not new_cores:  # 简单路径                           │
│      ├─ if old_cores:                                      │
│      │   → "吸收(Absorption)":                             │
│      │     label = max(old_cores的标签)                    │
│      │     objects.set_label(object_inserted, label)       │
│      └─ else:                                              │
│        → "噪声(Noise)":                                    │
│          objects.set_label(object_inserted, NOISE)         │
│      └─ 返回，流程结束                                     │
│    else:  # 复杂路径，继续 Step 4-7                        │
│                                                             │
│  Step 4: 计算更新种子集                                      │
│    update_seeds = _get_update_seeds(new_cores)             │
│    ↓                                                        │
│    └─ seeds = ∪ { core_neighbor |                         │
│                   core_neighbor ∈ new_core.neighbors       │
│                   AND core_neighbor.is_core }              │
│    // 更新种子 = 所有新核心的核心邻居集合                    │
│                                                             │
│  Step 5: 在更新种子中找连通分量                              │
│    components =                                            │
│      objects.get_connected_components_within_objects(      │
│        update_seeds)                                       │
│    ↓                                                        │
│    ├─ 提取子图: subgraph = graph.subgraph(node_ids)        │
│    ├─ 找连通分量: rx.connected_components(subgraph)        │
│    └─ 转换回 Object 集合                                   │
│                                                             │
│  Step 6: 处理每个连通分量                                    │
│    for component in components:                            │
│      effective_labels = 获取component中有效标签             │
│      // 有效标签 = 排除 UNCLASSIFIED(-2) 和 NOISE(-1)      │
│                                                             │
│      if not effective_labels:                              │
│        → "创建(Creation)":                                 │
│          new_label = objects.get_next_cluster_label()      │
│          objects.set_labels(component, new_label)          │
│      else:                                                 │
│        → "吸收+合并(Absorption/Merge)":                    │
│          max_label = max(effective_labels)                 │
│          objects.set_labels(component, max_label)          │
│          for label in effective_labels:                    │
│            objects.change_labels(label, max_label)         │
│                                                             │
│  Step 7: 更新新核心周围的边界/噪声对象                       │
│    _set_cluster_label_around_new_core_neighbors(new_cores) │
│    ↓                                                        │
│    └─ for new_core in new_cores:                           │
│        label = objects.get_label(new_core)                 │
│        objects.set_labels(new_core.neighbors, label)       │
│        // 新核心的所有邻居继承其标签                         │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 详细步骤解析

#### Step 1: `objects.insert_object(value)`

```python
def insert_object(self, value):
    object_id = hash_(value)  # xxhash.xxh64
    
    # 情况1: 重复点
    if object_id in self._object_id_to_node_id:
        obj = self._get_object_from_object_id(object_id)
        obj.count += 1
        for neighbor in obj.neighbors:
            neighbor.neighbor_count += 1
        return obj
    
    # 情况2: 新点
    new_object = Object(object_id, self.min_pts)
    
    # 1. 加入图
    node_id = self.graph.add_node(new_object)
    new_object.node_id = node_id
    self._object_id_to_node_id[object_id] = node_id
    
    # 2. 设置初始标签
    self.set_label_of_inserted_object(new_object)  # 标签 = UNCLASSIFIED
    
    # 3. 加入空间索引
    self.neighbor_searcher.insert(value, object_id)
    
    # 4. 更新邻居关系
    self._update_neighbors_during_insertion(new_object, value)
    
    return new_object
```

**关键点**：
- 重复点不创建新Object，只增加count并更新邻居的neighbor_count
- 新点的neighbors初始化时包含自身（`self.neighbors = {self}`）

#### Step 1e: `_update_neighbors_during_insertion`

```python
def _update_neighbors_during_insertion(self, object_inserted, new_value):
    # 查询新点的ε邻居
    neighbors = self._get_neighbors(new_value)
    
    for obj in neighbors:
        # 新点成为obj的邻居
        obj.neighbor_count += 1
        
        if obj.id != object_inserted.id:  # 不是自身
            # 反向计数：obj的count贡献给新点
            object_inserted.neighbor_count += obj.count
            
            # 双向加入邻居集合
            obj.neighbors.add(object_inserted)
            object_inserted.neighbors.add(obj)
            
            # 图中加边
            self.graph.add_edge(
                object_inserted.node_id, 
                obj.node_id, 
                None
            )
```

**邻居数计算示例**：
```
假设 min_pts = 3
已有对象 A(count=1), B(count=2, 重复点)
插入新对象 C(count=1)

如果 A, B, C 互为邻居:
- A.neighbor_count = A.count + B.count + C.count = 1 + 2 + 1 = 4
- B.neighbor_count = 1 + 2 + 1 = 4
- C.neighbor_count = 1 + 2 + 1 = 4

A, B, C 都是核心对象 (4 >= 3)
```

#### Step 2: `_separate_core_neighbors_by_novelty`

```python
def _separate_core_neighbors_by_novelty(self, object_inserted):
    new_cores = set()
    old_cores = set()
    
    for obj in object_inserted.neighbors:
        if obj.neighbor_count == self.min_pts:
            # 恰好达到minPts → 刚刚成为核心
            new_cores.add(obj)
        elif obj.neighbor_count > self.min_pts:
            # 已经超过minPts → 之前就是核心
            old_cores.add(obj)
    
    # 特殊处理：被插入对象自身
    if object_inserted in old_cores:
        old_cores.remove(object_inserted)
        new_cores.add(object_inserted)
        # 即使neighborCount > minPts，被插入对象也视为新核心
    
    return new_cores, old_cores
```

**为什么这样分类？**
- `new_cores`: 这些对象**因为本次插入**而成为核心，它们的邻居可能需要重新分配标签
- `old_cores`: 这些对象**之前就是核心**，只需要让新对象"吸收"到它们的聚类中

#### Step 4: `_get_update_seeds`

```python
def _get_update_seeds(self, new_core_neighbors):
    seeds = set()
    
    for new_core in new_core_neighbors:
        # 只取核心邻居
        core_neighbors = [
            obj for obj in new_core.neighbors
            if obj.neighbor_count >= self.min_pts
        ]
        seeds.update(core_neighbors)
    
    return seeds
```

**更新种子的含义**：
- 这些是**可能需要更新标签的核心对象**
- 它们通过新核心连接在一起，可能形成新的连通分量或合并现有聚类

#### Step 6: 处理连通分量

```python
for component in connected_components_in_update_seeds:
    effective_labels = self._get_effective_cluster_labels_of_objects(component)
    // effective_labels = {label | label ∉ {-2, -1}}
    
    if not effective_labels:
        # 【创建 Creation】
        # component中全是之前未分类或噪声的对象
        next_label = self.objects.get_next_cluster_label()
        self.objects.set_labels(component, next_label)
        
    else:
        # 【吸收 Absorption / 合并 Merge】
        max_label = max(effective_labels)
        
        # 先将component内所有对象统一到max_label
        self.objects.set_labels(component, max_label)
        
        # 再将所有旧标签的聚类合并到max_label
        for label in effective_labels:
            self.objects.change_labels(label, max_label)
```

**三种情况**：

1. **Creation（创建）**：
   - 场景：component中全是UNCLASSIFIED或NOISE对象
   - 操作：创建新聚类，分配新标签

2. **Absorption（吸收）**：
   - 场景：component中有1个有效标签
   - 操作：所有对象吸收进该标签对应的聚类

3. **Merge（合并）**：
   - 场景：component中有多个有效标签（如{0, 1}）
   - 操作：将所有旧标签合并到max_label（如都改为1）

**为什么选择max_label？**
- 保证标签单调递增，避免冲突
- 简化实现：`get_next_cluster_label() = max(labels) + 1`

#### Step 7: `_set_cluster_label_around_new_core_neighbors`

```python
def _set_cluster_label_around_new_core_neighbors(self, new_core_neighbors):
    for obj in new_core_neighbors:
        label = self.objects.get_label(obj)
        self.objects.set_labels(obj.neighbors, label)
```

**作用**：
- 新核心对象的所有邻居（包括边界对象和噪声）继承该核心的标签
- 这确保了DBSCAN的密度可达性：如果对象A是核心，其邻域内的所有对象都属于A的聚类

---

## 4. 删除流程详解

### 4.1 入口方法

```python
def delete(X):
    """删除多个数据点"""
    X = input_check(X)
    for ix, value in enumerate(X):
        obj = self._objects.get_object(value)
        if obj:
            self._deleter.delete(obj)
        else:
            warnings.warn(f"Object at position {ix} not found")
```

### 4.2 单点删除完整流程

```
┌──────────────────────────────────────────────────────────────┐
│                   单点删除完整流程                            │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  Step 1: 从对象集中删除该点                                  │
│    objects.delete_object(object_to_delete)                   │
│    ↓                                                         │
│    ├─ obj.count -= 1                                        │
│    ├─ remove_from_data = (obj.count == 0)                   │
│    ├─ 对所有邻居: neighbor.neighbor_count -= 1              │
│    ├─ if remove_from_data:                                  │
│    │   ├─ 从邻居集合移除: neighbor.neighbors.remove(obj)    │
│    │   ├─ 从图中移除: graph.remove_node(obj.node_id)        │
│    │   ├─ 从空间索引移除: neighbor_searcher.delete(obj.id)  │
│    │   └─ 从标签系统移除: delete_label_of_deleted_object    │
│    └─ 返回                                                   │
│                                                              │
│  Step 2: 找出失去核心属性的对象                               │
│    ex_cores =                                                │
│      _get_objects_that_lost_core_property(object_deleted)    │
│    ↓                                                         │
│    ├─ threshold = min_pts - 1                               │
│    ├─ for neighbor in object_deleted.neighbors:             │
│    │   if neighbor.neighbor_count == threshold:             │
│    │     yield neighbor  // 不再是核心                      │
│    └─ if object_deleted.is_core:                            │
│        yield object_deleted  // 被删除对象本身              │
│                                                              │
│  Step 3: 计算更新种子和前核心的非核心邻居                     │
│    update_seeds, non_core_neighbors =                        │
│      _get_update_seeds_and_non_core_neighbors_of_ex_cores(  │
│        ex_cores, object_deleted)                             │
│    ↓                                                         │
│    ├─ update_seeds = {}                                     │
│    ├─ non_core_neighbors = {}                               │
│    ├─ for ex_core in ex_cores:                              │
│    │   for neighbor in ex_core.neighbors:                   │
│    │     if neighbor.is_core:                               │
│    │       update_seeds.add(neighbor)  // 仍是核心          │
│    │     else:                                              │
│    │       non_core_neighbors.add(neighbor)  // 边界对象    │
│    └─ if object_deleted.count == 0:                         │
│        update_seeds.discard(object_deleted)                 │
│        non_core_neighbors.discard(object_deleted)           │
│                                                              │
│  Step 4: 检查是否需要处理分裂                                │
│    if update_seeds:                                          │
│      update_seeds_by_cluster =                               │
│        _group_objects_by_cluster(update_seeds)               │
│      // 按标签分组                                           │
│                                                              │
│      for seeds in update_seeds_by_cluster.values():          │
│        components = _find_components_to_split_away(seeds)    │
│        for component in components:                          │
│          new_label = objects.get_next_cluster_label()        │
│          objects.set_labels(component, new_label)            │
│          // 分裂出的分量获得新标签                            │
│                                                              │
│  Step 5: 更新前核心的边界对象标签                             │
│    _set_each_border_object_labels_to_largest_around(         │
│      non_core_neighbors)                                     │
│    ↓                                                         │
│    ├─ for obj in non_core_neighbors:                        │
│    │   labels = _get_cluster_labels_in_neighborhood(obj)    │
│    │   // labels = {neighbor.label | neighbor.is_core}      │
│    │   if not labels:                                       │
│    │     labels.add(NOISE)  // 没有核心邻居 → 变为噪声      │
│    │   cluster_updates[obj] = max(labels)                   │
│    └─ 执行更新:                                             │
│      for obj, new_label in cluster_updates.items():         │
│        objects.set_label(obj, new_label)                    │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 4.3 详细步骤解析

#### Step 1: `objects.delete_object(obj)`

```python
def delete_object(self, obj):
    obj.count -= 1
    remove_from_data = (obj.count == 0)  # 没有重复点了
    
    # 更新所有邻居的邻居数
    for neighbor in obj.neighbors:
        neighbor.neighbor_count -= 1
        if remove_from_data and neighbor.id != obj.id:
            neighbor.neighbors.remove(obj)  # 从邻居集合移除
    
    if remove_from_data:
        # 完全移除
        self._delete_graph_metadata(obj)
        self.neighbor_searcher.delete(obj.id)
        self.delete_label_of_deleted_object(obj)
```

**关键点**：
- 如果有重复点（count > 0），只是count减1，不真正删除
- 只有count=0时才从图、空间索引、标签系统中完全移除

#### Step 2: `_get_objects_that_lost_core_property`

```python
def _get_objects_that_lost_core_property(self, object_deleted):
    threshold = self.min_pts - 1
    
    # 邻居中neighbor_count降到threshold的对象
    for obj in object_deleted.neighbors:
        if obj.neighbor_count == threshold:
            yield obj  // 之前是核心，现在不是了
    
    # 如果被删除对象本身是核心
    if object_deleted.is_core:
        yield object_deleted
```

**ex-cores的含义**：
- 这些对象**失去了核心属性**（从核心变为非核心）
- 它们的邻居可能需要重新分配标签或检测分裂

#### Step 3: `_get_update_seeds_and_non_core_neighbors_of_ex_cores`

```python
def _get_update_seeds_and_non_core_neighbors_of_ex_cores(
        self, ex_cores, object_deleted):
    
    update_seeds = set()
    non_core_neighbors_of_ex_cores = set()
    
    for ex_core in ex_cores:
        for neighbor in ex_core.neighbors:
            if neighbor.is_core:
                update_seeds.add(neighbor)        // 仍是核心
            else:
                non_core_neighbors_of_ex_cores.add(neighbor)  // 边界对象
    
    if object_deleted.count == 0:
        update_seeds.discard(object_deleted)
        non_core_neighbors_of_ex_cores.discard(object_deleted)
    
    return update_seeds, non_core_neighbors_of_ex_cores
```

**两类对象的区别**：
- `update_seeds`: 前核心周围**仍然是核心**的邻居 → 用于检测分裂
- `non_core_neighbors`: 前核心周围**不再是核心**的邻居（边界对象）→ 需要重新分配标签

#### Step 4: 分裂检测与处理

```python
if update_seeds:
    # 按聚类标签分组
    update_seeds_by_cluster = self._group_objects_by_cluster(update_seeds)
    
    for seeds in update_seeds_by_cluster.values():
        # 对每组种子检测分裂
        components = self._find_components_to_split_away(seeds)
        
        for component in components:
            new_label = self.objects.get_next_cluster_label()
            self.objects.set_labels(component, new_label)
```

**为什么要按标签分组？**
- 只有同一聚类的种子才可能因删除而分裂
- 不同聚类的种子互不影响

#### Step 4b: `_find_components_to_split_away` — BFS分裂检测

这是删除流程中最复杂的部分：

```python
def _find_components_to_split_away(self, seed_objects):
    # 快速返回
    if len(seed_objects) == 1:
        return []  // 单个种子不会分裂
    
    if _objects_are_neighbors_of_each_other(seed_objects):
        return []  // 所有种子互为邻居 → 完全连通，不会分裂
    
    # BFS分裂检测
    finder = BFSComponentFinder(self.objects.graph)
    seed_node_ids = [obj.node_id for obj in seed_objects]
    components = finder.find_components(seed_node_ids)
    return components
```

**BFS分裂检测算法详解**：

```
┌──────────────────────────────────────────────────────────┐
│              BFS分裂检测算法 (find_components)             │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  Step P1: 预处理 — 创建虚拟源节点                         │
│    origin_object = Object("ORIGIN", 0)                  │
│    origin_node_id = graph.add_node(origin_object)        │
│    for seed_node_id in seeds:                            │
│      graph.add_edge(origin_node_id, seed_node_id)        │
│    // 虚拟节点连接所有种子，实现多源BFS                  │
│                                                          │
│  Step P2: 执行BFS遍历                                   │
│    rx.bfs_search(graph, [origin_node_id], visitor=self)  │
│    // visitor 即 BFSComponentFinder 自身                 │
│    // 调用 discover_vertex, tree_edge, gray_target_edge  │
│    // finish_vertex 等回调方法                           │
│                                                          │
│  Step P3: 后处理 — 清理虚拟节点                         │
│    graph.remove_node(origin_node_id)                     │
│    del seed_to_component[origin_node_id]                 │
│                                                          │
│    // 丢弃未被完全遍历的分量（即保留的主分量）           │
│    remaining_node = queue.popleft()                      │
│    remaining_seed = node_to_seed[remaining_node]         │
│    del seed_to_component[remaining_seed]                 │
│                                                          │
│    return seed_to_component.values()                     │
│    // 返回的是需要分裂出去的分量                         │
│                                                          │
└──────────────────────────────────────────────────────────┘

BFS事件回调:

discover_vertex(vertex_node_id):
  // 首次发现节点时
  if vertex_node_id not in node_to_seed:
    node_to_seed[vertex_node_id] = vertex_node_id
    seed_to_component[vertex_node_id].add(graph[vertex_node_id])
  
  if graph[vertex_node_id].is_core:
    queue.append(vertex_node_id)  // 核心对象继续遍历
  else:
    raise PruneSearch  // 非核心对象 → 剪枝

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
  
  if source_seed != target_seed AND graph[target].is_core:
    // 不同种子的分量通过核心对象相遇 → 合并分量
    objects_to_merge = seed_to_component[target_seed]
    for obj in objects_to_merge:
      node_to_seed[obj.node_id] = source_seed
    seed_to_component[source_seed].update(objects_to_merge)
    del seed_to_component[target_seed]

finish_vertex(_):
  queue.popleft()
  if _same_seeds():  // 队列中所有节点的种子相同
    raise StopSearch  // BFS提前终止
```

**核心思想**：
1. **多源BFS**：从多个种子同时出发，每个节点继承其来源的种子标签
2. **密度连通**：只有核心对象才能传递连通性（非核心对象处剪枝）
3. **分量合并**：当两个不同种子的分量通过核心对象相遇时，它们合并（说明密度连通，不会分裂）
4. **提前终止**：当队列中只剩一种种子时，说明所有需要分裂出去的分量都已找完
5. **返回分裂分量**：返回的是**需要分裂出去的分量**，保留主分量不变

**示例**：
```
假设聚类1包含: A-B-C-D-E-F
其中C是唯一连接左右两组的桥接点

删除C后:
- ex_cores = {C, B, D} (假设B,D因删除C而失去核心属性)
- update_seeds = {A, E, F} (B,D周围仍是核心的邻居)
- A属于左组，E,F属于右组

BFS分裂检测:
- 从A, E, F三个种子出发
- A遍历到左组所有核心
- E,F遍历到右组所有核心
- 两组不会相遇（没有核心桥接）
- BFS终止时，左组和右组分属不同分量
- 返回需要分裂的分量（较小的那个，如右组{E,F}）
- 右组获得新标签2，左组保留标签1
```

#### Step 5: `_set_each_border_object_labels_to_largest_around`

```python
def _set_each_border_object_labels_to_largest_around(self, objects_to_set):
    cluster_updates = {}
    
    for obj in objects_to_set:
        labels = self._get_cluster_labels_in_neighborhood(obj)
        // labels = {neighbor.label | neighbor ∈ obj.neighbors AND neighbor.is_core}
        
        if not labels:
            labels.add(CLUSTER_LABEL_NOISE)  // 没有核心邻居 → 变为噪声
        
        cluster_updates[obj] = max(labels)
        // 选择最大的标签（"最新"的聚类）
    
    for obj, new_label in cluster_updates.items():
        self.objects.set_label(obj, new_label)
```

**边界对象标签分配规则**：
- 如果一个边界对象有多个核心邻居属于不同聚类，它选择**标签值最大**的那个聚类
- 如果没有核心邻居，它变为噪声
- 这保证了边界对象总是归属于某个存在的聚类（如果有的话）

---

## 5. 关键子算法

### 5.1 连通分量查找

```python
def get_connected_components_within_objects(self, objects: Set[Object]):
    if len(objects) == 1:
        return [objects]
    
    node_ids = [obj.node_id for obj in objects]
    subgraph = self.graph.subgraph(node_ids)
    components_as_ids = rx.connected_components(subgraph)
    
    return [
        {subgraph[node_id] for node_id in component}
        for component in components_as_ids
    ]
```

**用途**：在插入流程Step 5中，找出更新种子中的连通分量。

### 5.2 标签合并

```python
def change_labels(self, change_from, change_to):
    if change_from == change_to:
        return
    
    affected_objects = self._label_to_objects.pop(change_from)
    self._label_to_objects[change_to].update(affected_objects)
    
    for obj in affected_objects:
        self._object_to_label[obj] = change_to
```

**用途**：在插入流程Step 6中，将多个聚类合并为一个。

### 5.3 邻居查询

```python
def query_neighbors(self, query_value):
    neighbor_indices = self._effective_searcher._get_neighbor_indices(query_value)
    for ix in neighbor_indices:
        yield self.ids[ix]  // 返回对象ID
```

**底层实现**：
- 低维数据：`cKDTree.query_ball_point(query_value, radius, p)`
- 高维数据：`sklearn.NearestNeighbors.radius_neighbors([query_value])`

---

## 6. 实现要点总结

### 6.1 核心设计原则

1. **增量更新而非重算**：
   - 利用已有聚类结果
   - 只更新受影响的局部区域
   - 保证结果与全量DBSCAN一致

2. **图的辅助作用**：
   - 存储邻域关系（边表示ε邻居）
   - 支持快速连通分量查找
   - 支持BFS遍历

3. **标签单调递增**：
   - 新聚类标签 = max(existing_labels) + 1
   - 合并时选择max_label
   - 避免标签冲突

4. **密度连通性**：
   - 只有核心对象才能传递连通性
   - 非核心对象在BFS中剪枝
   - 这符合DBSCAN的定义

### 6.2 四种典型场景

| 场景 | 触发条件 | 处理方式 |
|------|---------|---------|
| **Creation（创建）** | 新核心连接的component中全是未分类/噪声对象 | 创建新聚类，分配新标签 |
| **Absorption（吸收）** | 新对象有核心邻居但无新核心产生 | 新对象吸收进最近的核心聚类 |
| **Merge（合并）** | 新核心连接的component中有多个有效标签 | 所有对象合并到max_label |
| **Split（分裂）** | 删除导致某聚类的核心不再密度连通 | 分裂出的分量获得新标签 |

### 6.3 重复点处理

```python
# 插入重复点
if object_id in self._object_id_to_node_id:
    obj.count += 1
    for neighbor in obj.neighbors:
        neighbor.neighbor_count += 1
    return obj

# 删除重复点
obj.count -= 1
remove_from_data = (obj.count == 0)  # 只有count=0才真正移除
```

**关键**：
- 相同坐标的点共享一个Object
- `count` 记录重复次数
- 每个重复点贡献其count值到所有邻居的 `neighbor_count`

### 6.4 性能优化点

1. **空间索引自适应**：
   - 低维用cKDTree（快）
   - 高维用sklearn（通用）

2. **邻居数动态维护**：
   - 插入/删除时更新 `neighbor_count`
   - 避免每次重新计算

3. **BFS提前终止**：
   - 当队列中只剩一种种子时停止
   - 减少不必要的遍历

4. **快速返回**：
   - 单个种子不会分裂
   - 所有种子互为邻居不会分裂

### 6.5 注意事项

⚠️ **常见陷阱**：

1. **neighbors包含自身**：
   - 每个对象的neighbors集合包含自己
   - 这符合DBSCAN定义（距离为0 ≤ eps）
   - 计算时要小心不要重复计数

2. **neighbor_count vs len(neighbors)**：
   - `neighbor_count` 是所有邻居的count之和
   - `len(neighbors)` 是邻居集合大小
   - 两者不等价（有重复点时）

3. **标签选择始终用max**：
   - 插入时：`max(old_core_labels)`
   - 合并时：`max(effective_labels)`
   - 边界对象：`max(core_neighbor_labels)`

4. **图与空间索引同步**：
   - 插入时：先加空间索引，再更新图和邻居
   - 删除时：先更新邻居，再从图和空间索引移除

5. **BFS只遍历核心对象**：
   - 非核心对象处剪枝（`raise PruneSearch`）
   - 这保证了密度连通的定义

---

## 7. Java实现建议

### 7.1 技术选型

| Python组件 | Java替代方案 | 推荐库 |
|-----------|------------|-------|
| `rustworkx.PyGraph` | 无向图 | JGraphT (`SimpleGraph`) |
| `scipy.spatial.cKDTree` | KD树 | Apache Commons Math (`KDTree`) |
| `sklearn.NearestNeighbors` | 邻居搜索 | Smile ML 或自实现 |
| `sortedcontainers.SortedList` | 有序列表 | `TreeSet<Long>` |
| `xxhash.xxh64` | 哈希函数 | Guava `Hashing.sha256()` 或 `Long.hashCode()` |
| `numpy.ndarray` | 数组 | `double[][]` |
| `defaultdict(set)` | 映射 | `HashMap<Integer, HashSet<Object>>` |
| `rx.bfs_search` | BFS遍历 | JGraphT `BreadthFirstIterator` 或自实现 |

### 7.2 核心类结构

```java
public class IncrementalDBSCAN {
    private double eps;
    private int minPts;
    private String metric;
    private double p;
    
    private Objects objects;
    private Inserter inserter;
    private Deleter deleter;
    
    public void insert(double[][] X) { ... }
    public void delete(double[][] X) { ... }
    public int[] getClusterLabels(double[][] X) { ... }
}

class Objects extends LabelHandler {
    private SimpleGraph<Object, DefaultEdge> graph;
    private Map<Long, Integer> objectIdToNodeId;
    private NeighborSearcher neighborSearcher;
    private int minPts;
    
    public Object insertObject(double[] value) { ... }
    public void deleteObject(Object obj) { ... }
    public List<Set<Object>> getConnectedComponents(Set<Object> objects) { ... }
}

class Object {
    private long id;
    private int nodeId;
    private int count;
    private Set<Object> neighbors;
    private int neighborCount;
    private int minPts;
    
    public boolean isCore() {
        return neighborCount >= minPts;
    }
}

class Inserter {
    private double eps;
    private int minPts;
    private Objects objects;
    
    public void insert(double[] objectValue) { ... }
}

class Deleter {
    private double eps;
    private int minPts;
    private Objects objects;
    
    public void delete(Object objectToDelete) { ... }
}

class BFSComponentFinder {
    private SimpleGraph<Object, DefaultEdge> graph;
    private Map<Integer, Set<Object>> seedToComponent;
    private Map<Integer, Integer> nodeToSeed;
    private Queue<Integer> queue;
    
    public List<Set<Object>> findComponents(List<Integer> seeds) { ... }
}
```

### 7.3 伪代码实现

#### 插入伪代码

```java
void insertPoint(double[] value) {
    // 1. 加入对象集，建立邻居关系
    Object obj = objects.insertObject(value);
    
    // 2. 分类核心邻居
    Set<Object> newCores = new HashSet<>();
    Set<Object> oldCores = new HashSet<>();
    for (Object neighbor : obj.getNeighbors()) {
        if (neighbor.getNeighborCount() == minPts) {
            newCores.add(neighbor);
        } else if (neighbor.getNeighborCount() > minPts) {
            oldCores.add(neighbor);
        }
    }
    if (oldCores.contains(obj)) {
        oldCores.remove(obj);
        newCores.add(obj);
    }
    
    // 3. 无新核心 → 简单路径
    if (newCores.isEmpty()) {
        if (!oldCores.isEmpty()) {
            int label = getMaxLabel(oldCores);
            objects.setLabel(obj, label);
        } else {
            objects.setLabel(obj, CLUSTER_LABEL_NOISE);
        }
        return;
    }
    
    // 4. 有新核心 → 计算更新种子
    Set<Object> seeds = new HashSet<>();
    for (Object newCore : newCores) {
        for (Object n : newCore.getNeighbors()) {
            if (n.getNeighborCount() >= minPts) {
                seeds.add(n);
            }
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
        objects.setLabels(newCore.getNeighbors(), label);
    }
}
```

#### 删除伪代码

```java
void deletePoint(Object objToDelete) {
    // 1. 从对象集移除
    objects.deleteObject(objToDelete);
    
    // 2. 找出失去核心属性的对象
    List<Object> exCores = new ArrayList<>();
    int threshold = minPts - 1;
    for (Object neighbor : objToDelete.getNeighbors()) {
        if (neighbor.getNeighborCount() == threshold) {
            exCores.add(neighbor);
        }
    }
    if (objToDelete.isCore()) {
        exCores.add(objToDelete);
    }
    
    // 3. 计算更新种子和前核心的非核心邻居
    Set<Object> updateSeeds = new HashSet<>();
    Set<Object> nonCoreNeighbors = new HashSet<>();
    for (Object exCore : exCores) {
        for (Object neighbor : exCore.getNeighbors()) {
            if (neighbor.isCore()) {
                updateSeeds.add(neighbor);
            } else {
                nonCoreNeighbors.add(neighbor);
            }
        }
    }
    if (objToDelete.getCount() == 0) {
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
        if (labels.isEmpty()) {
            labels.add(CLUSTER_LABEL_NOISE);
        }
        objects.setLabel(obj, Collections.max(labels));
    }
}
```

### 7.4 关键注意事项

✅ **必须遵守的规则**：

1. **邻居数计算**：
   ```java
   // 错误：neighborCount = neighbors.size()
   // 正确：neighborCount = sum(neighbor.count for neighbor in neighbors)
   ```

2. **标签选择**：
   ```java
   // 始终选择最大标签
   int label = Collections.max(labels);
   ```

3. **密度连通**：
   ```java
   // BFS中只有核心对象才能传递连通性
   if (!obj.isCore()) {
       continue;  // 剪枝
   }
   ```

4. **neighbors包含自身**：
   ```java
   // 初始化时
   this.neighbors = new HashSet<>();
   this.neighbors.add(this);  // 包含自身
   ```

5. **重复点处理**：
   ```java
   // 插入时
   if (existingObject != null) {
       existingObject.count++;
       for (Object neighbor : existingObject.neighbors) {
           neighbor.neighborCount++;
       }
       return existingObject;
   }
   ```

6. **空间索引重建**：
   ```java
   // 每次插入后重建KDTree
   void insert(double[] value, long id) {
       // 插入到数组
       rebuildTree();  // 重建索引
   }
   ```

### 7.5 性能优化建议

1. **延迟重建空间索引**：
   - 批量插入后再重建
   - 或者使用增量更新的KDTree实现

2. **缓存邻居查询结果**：
   - 对于频繁查询的点，缓存其邻居列表

3. **并行处理**：
   - 批量插入/删除时，可以并行处理独立的点

4. **内存优化**：
   - 使用primitive类型而非包装类
   - 考虑使用Troove或fastutil库

5. **JVM调优**：
   ```
   -Xmx4g -Xms2g  // 根据数据量调整堆大小
   -XX:+UseG1GC   // 使用G1垃圾收集器
   ```

---

## 8. 完整执行示例

### 示例1: 创建新聚类 (Creation)

**场景**：`eps=1, minPts=3`

```
1. 插入点 A=(0,0)
   - neighborCount=1
   - 标签=NOISE (-1)

2. 插入点 B=(0.5,0)
   - A和B互为邻居
   - A.neighborCount=2, B.neighborCount=2
   - B标签=NOISE (没有新核心)

3. 插入点 C=(0,0.5)
   - A,B,C互为邻居
   - A.neighborCount=3 ≥ minPts → A成为核心! (新核心)
   - B.neighborCount=3 ≥ minPts → B成为核心! (新核心)
   - C.neighborCount=3 ≥ minPts → C成为核心! (新核心)
   
   - new_core_neighbors = {A, B, C}
   - update_seeds = {A, B, C} (它们互为核心邻居)
   - 连通分量: {A,B,C} (全连通)
   - effective_labels = 空 (全是NOISE/UNCLASSIFIED)
   
   → 创建新聚类, 标签=0
   → A,B,C标签都设为0
   → 邻居继承: A.neighbors={A,B,C}标签全为0 ✓
```

### 示例2: 吸收 (Absorption)

**场景**：已有聚类标签0，插入一个边界点

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

**场景**：两个独立聚类，新插入点将它们连接

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

**场景**：一个聚类通过"桥接点"连接，删除桥接点导致分裂

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

## 9. 总结

### 9.1 算法核心思想

增量DBSCAN的核心在于**局部更新**：
- **插入时**：识别新产生的核心对象，更新它们周围的连通分量
- **删除时**：识别失去核心属性的对象，检测可能的分裂并重新分配边界对象标签

### 9.2 关键创新点

1. **图的引入**：用图存储邻域关系，支持高效的连通分量查找和BFS遍历
2. **动态维护邻居数**：避免重复计算，提高性能
3. **BFS分裂检测**：通过多源BFS和种子标签传播，高效检测聚类分裂
4. **标签单调递增**：简化合并逻辑，避免冲突

### 9.3 适用场景

✅ **适合**：
- 流式数据聚类
- 频繁增删的动态数据集
- 需要实时聚类结果的场景

❌ **不适合**：
- 一次性静态数据（直接用标准DBSCAN更简单）
- 极高维数据（空间索引效率低）
- 大规模批量更新（可能不如重算快）

### 9.4 实现难度评估

| 模块 | 难度 | 说明 |
|------|------|------|
| 基础数据结构 | ⭐⭐ | Object, Objects, LabelHandler |
| 空间索引 | ⭐⭐⭐ | 需要选择合适的KDTree实现 |
| 插入流程 | ⭐⭐⭐ | 逻辑较清晰，但细节多 |
| 删除流程 | ⭐⭐⭐⭐⭐ | BFS分裂检测复杂 |
| 图操作 | ⭐⭐⭐ | 需要图论基础知识 |

### 9.5 学习建议

1. **先理解标准DBSCAN**：确保掌握核心对象、密度可达等概念
2. **从小规模示例开始**：手动模拟几个点的插入/删除过程
3. **重点攻克BFS分裂检测**：这是最复杂的部分，多画图理解
4. **参考现有实现**：结合本文档和源代码对照学习
5. **编写单元测试**：覆盖Creation、Absorption、Merge、Split四种场景

---

**文档版本**: v1.0  
**最后更新**: 2026-04-17  
**参考源码**: incdbscan Python实现  
**作者**: AI Assistant
