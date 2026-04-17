# 增量 DBSCAN 实现分析

## 1. 原始 DBSCAN 回顾

原始 DBSCAN（Ester et al. 1996）的核心概念：

| 概念 | 定义 |
|------|------|
| **ε-邻域** | 以点 p 为圆心、半径为 ε 的区域内的所有点 |
| **核心点** | ε-邻域内点数 ≥ MinPts 的点 |
| **边界点** | 不是核心点，但在某核心点的 ε-邻域内 |
| **噪声点** | 既非核心点也非边界点 |
| **直接密度可达** | 点 q 在核心点 p 的 ε-邻域内 |
| **密度可达** | 经过一系列直接密度可达关系连接 |
| **簇** | 密度可达的最大点集 |

**原始算法流程**（每次更新都要从头运行）：
1. 对每个未访问点，计算其 ε-邻域
2. 若邻域内点数 ≥ MinPts，则以该点为核心创建/扩展簇
3. 递归地将所有密度可达点加入同一簇
4. 时间复杂度：O(n²)（暴力），O(n log n)（使用空间索引）

---

## 2. 增量 DBSCAN 的核心思想

增量 DBSCAN（Ester et al. 1998，基于 VLDB 论文）的关键洞察：

> **插入或删除一个点时，只有该点 ε-邻域内的点的聚类状态可能改变。**

因此，不需要重跑整个算法，只需在变化点的局部邻域内做局部更新。每次更新后的结果与对整个数据集重新运行 DBSCAN 完全等价。

---

## 3. 数据结构设计

### 3.1 Object（`_object.py`）

每个数据点被包装为一个 `Object`，维护：

```
Object:
  id            ← xxhash 哈希值（唯一标识点）
  node_id       ← 在 rustworkx 图中的节点 ID
  count         ← 该点被插入的次数（支持重复点）
  neighbor_count← ε-邻域内的邻居数（不含自身）
  neighbors     ← 邻居 Object 集合（含自身）
  min_pts       ← 核心点阈值
  is_core       ← neighbor_count >= min_pts（属性）
```

`is_core` 是实时计算的属性，随 `neighbor_count` 变化而自动更新。

### 3.2 Objects / 邻居图（`_objects.py`）

维护一个 **`rustworkx.PyGraph` 无向图**：
- **节点**：每个唯一数据点（Object）
- **边**：两点互为 ε-邻居时存在边

这个图是算法的核心数据结构，同时被 Inserter、Deleter 和 BFSComponentFinder 共享使用。

### 3.3 LabelHandler（`_labels.py`）

维护两个双向映射：
- `_object_to_label`：点 → 簇标签
- `_label_to_objects`：簇标签 → 点集合

标签含义：
- `-2`：未分类（刚插入还未被归类）
- `-1`：噪声
- `0, 1, 2, ...`：有效簇（递增分配，永不复用）

### 3.4 NeighborSearcher（`_neighbor_searcher.py`）

自适应选择底层空间索引：
- **低维（≤15 维）+ Minkowski 距离 + p≥1**：使用 `scipy.cKDTree`（更快）
- **高维 或 非 Minkowski 距离**：使用 `sklearn.NearestNeighbors`（ball tree）

每次插入/删除都重建索引（`_rebuild()`），维护一个 `SortedList` 来保证点与索引位置的对应关系。

---

## 4. 插入算法（`_inserter.py`）

### 4.1 整体流程

```
insert(point p):
1. 将 p 加入数据结构，更新邻居的 neighbor_count
2. 分离 "新核心点" 与 "旧核心点"
3. 若无新核心点 → 简单处理（噪声 or 吸收）
4. 若有新核心点 → 计算 update_seeds → 找连通分量 → 合并/创建簇
5. 将新核心点邻域内所有点重新贴标签
```

### 4.2 关键概念：新核心点 vs 旧核心点

插入 p 会使 p 本身及其所有邻居的 `neighbor_count` 加 1。
其中，恰好达到 MinPts 阈值（即 `neighbor_count == min_pts`）的邻居称为**新核心点**（new_core）——它们是因为插入 p 才刚刚成为核心点的。

```python
def _separate_core_neighbors_by_novelty(self, object_inserted):
    for obj in object_inserted.neighbors:
        if obj.neighbor_count == self.min_pts:   # 刚好达到阈值 → 新核心
            new_cores.add(obj)
        elif obj.neighbor_count > self.min_pts:  # 之前就是核心 → 旧核心
            old_cores.add(obj)
    # 插入点自身若成为核心，也算新核心
```

### 4.3 无新核心点时（快速路径）

- 有旧核心邻居 → **吸收（Absorption）**：p 归入邻居所在簇中标签最大的那个
- 无任何核心邻居 → **噪声（Noise）**：p 标记为噪声

### 4.4 有新核心点时（完整更新）

**Step 1：计算 update_seeds**

收集所有新核心点的核心邻居（即本就是核心点的邻居）：

```python
update_seeds = { 每个新核心点 的所有 is_core 邻居 }
```

这些 seeds 是可能需要合并的"种子核心点"。

**Step 2：在 update_seeds 子图中找连通分量**

利用 `rustworkx` 提取子图并求连通分量：

```python
subgraph = graph.subgraph([seed.node_id for seed in update_seeds])
components = rx.connected_components(subgraph)
```

每个连通分量代表一组相互密度可达的核心点集。

**Step 3：对每个连通分量决定创建还是合并**

```
分量内无有效簇标签 → 创建新簇（Creation）
分量内有有效簇标签 → 合并到标签最大的簇（Merge / Absorption）
```

**Step 4：传播标签到边界/噪声点**

对每个新核心点，将其标签扩散给所有 ε-邻居（包括边界点、噪声点、插入的新点 p）：

```python
for obj in new_core_neighbors:
    label = objects.get_label(obj)
    objects.set_labels(obj.neighbors, label)
```

### 4.5 论文中的四种情况对应

| 论文情况 | 代码实现路径 |
|---------|------------|
| **Noise**：p 既无新核心也无旧核心邻居 | `label = CLUSTER_LABEL_NOISE` |
| **Absorption**：p 被旧核心吸收 | `label = max(old_core 邻居标签)` |
| **Creation**：分量内无已有簇 | `label = get_next_cluster_label()` |
| **Merge**：分量内有多个已有簇 | `change_labels(old_label → max_label)` |

---

## 5. 删除算法（`_deleter.py`）

### 5.1 整体流程

```
delete(point p):
1. 将 p 从数据结构移除，更新邻居的 neighbor_count
2. 找出因此失去核心性的点（ex_cores）
3. 计算 update_seeds（ex_cores 的核心邻居）
4. 对同簇的 update_seeds 检测是否需要分裂
5. 更新非核心邻居的标签（可能变噪声）
```

### 5.2 找失去核心性的点（ex_cores）

删除 p 使所有邻居的 `neighbor_count` 减 1。
恰好降到 `min_pts - 1` 的邻居失去了核心性：

```python
def _get_objects_that_lost_core_property(self, object_deleted):
    threshold = self.min_pts - 1
    for obj in object_deleted.neighbors:
        if obj.neighbor_count == threshold:  # 刚好低于阈值
            yield obj
    if object_deleted.is_core:              # 被删点自身若是核心也算
        yield object_deleted
```

### 5.3 检测簇分裂（核心难点）

删除一个核心点可能将原来连通的簇"切断"，需要判断是否发生了分裂。

**优化：先做快速判断**

```python
if len(seeds) == 1:                              # 只有一个种子 → 不分裂
    return []
if _objects_are_neighbors_of_each_other(seeds): # 种子互为邻居 → 仍连通
    return []
```

**BFS 连通分量查找（`_bfscomponentfinder.py`）**

如果快速判断不能排除分裂，使用 BFSComponentFinder 做多源 BFS：

1. 在图中临时添加一个"虚假原点"节点，连接所有 seed
2. 从虚假原点出发做 BFS，**只沿核心点扩展**（非核心点处 PruneSearch）
3. 用 `_node_to_seed` 跟踪每个节点属于哪个 seed 的分量
4. 当队列中所有节点都属于同一个 seed 时，提前停止（StopSearch）——剩余的其他 seed 分量即为需要分裂出去的部分
5. 清理虚假原点，返回需要分裂出的各连通分量

分裂出的每个分量都被赋予新的簇标签：
```python
for component in components:
    objects.set_labels(component, objects.get_next_cluster_label())
```

### 5.4 更新边界点标签

ex_cores 的非核心邻居可能：
- 仍在其他核心点邻域内 → 继续留在那个核心点所在的簇（取最大标签）
- 不在任何核心点邻域内 → 变成噪声（标签 = -1）

```python
def _get_cluster_labels_in_neighborhood(self, obj):
    return { objects.get_label(n) for n in obj.neighbors if n.is_core }
# 若结果为空集 → 噪声
```

---

## 6. 与原始 DBSCAN 的核心差异

| 维度 | 原始 DBSCAN | 增量 DBSCAN |
|------|------------|------------|
| **更新策略** | 每次从零重算全量 | 局部更新，仅处理变化点的邻域 |
| **时间复杂度（单次更新）** | O(n log n) ~ O(n²) | O(k log k)，k 为邻域点数 |
| **数据结构** | 无需持久化 | 维护邻居图、空间索引、标签映射 |
| **簇标签** | 每次重算标签不保证一致 | 单调递增，已有标签不改变（除合并/分裂外） |
| **重复点** | 通常不支持 | 通过 `count` 字段支持重复插入同一点 |
| **簇分裂检测** | 不需要 | 删除时需要 BFS 检测连通性变化 |
| **序列化** | 不适用 | 支持（`__getstate__`/`__setstate__` 重建邻居集合） |
| **邻居索引** | 一次性构建 | 动态增删，自适应 cKDTree / sklearn |

### 6.1 增量算法正确性保证

增量算法的正确性依赖于以下性质：
- **局部性**：插入/删除点 p 只影响 p 的 ε-邻域内的点的核心性和簇归属
- **等价性**：每次操作后，结果等价于对当前完整数据集重新运行 DBSCAN

### 6.2 与论文的偏差

代码中有一处与原始论文不同（有注释说明）：

```python
# 若无新核心，但有旧核心邻居 → 取标签最大的簇
# "This is similar to case Absorption in the paper but not defined there."
label_of_new_object = max([
    self.objects.get_label(obj) for obj in old_core_neighbors
])
```

论文中的 Absorption 情况是指插入的新点成为了旧核心点的边界点；代码在此基础上还处理了"插入点不改变任何核心性但本身需要被归类"的情况，使用标签最大值策略保持与 DBSCAN 等价性。

---

## 7. 架构图

```
IncrementalDBSCAN
├── insert(X)  ──→  Inserter.insert(value)
│                       ↓
│               Objects.insert_object()    ← NeighborSearcher（空间索引）
│               _separate_core_by_novelty()
│               _get_update_seeds()
│               get_connected_components() ← rustworkx.connected_components
│               set_labels() / change_labels() ← LabelHandler
│
└── delete(X)  ──→  Deleter.delete(obj)
                        ↓
                Objects.delete_object()    ← NeighborSearcher
                _get_objects_that_lost_core_property()
                _find_components_to_split_away() ← BFSComponentFinder
                _set_each_border_object_labels()  ← LabelHandler
```

---

## 8. 小结

增量 DBSCAN 的本质是：**将全量算法中"找所有密度可达点"的过程，转化为"在已有聚类结果基础上，仅修复受影响的局部区域"的过程**。关键设计决策有三：

1. **邻居图**：将 ε-邻居关系持久化为图，避免每次重新计算距离
2. **核心性变化驱动更新**：只有当一个点的核心性改变时，才需要重新评估其簇归属
3. **BFS 检测分裂**：删除时的分裂检测是唯一需要图遍历的地方，利用早停优化避免全图 BFS
