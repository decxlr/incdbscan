## 原DBSCAN原理

```
输入: points (全部待聚类点), eps (邻域半径), minPts (最小点数)
输出: clusters (若干 Cluster<T>，噪声点不出现在任何簇中)

FOR 每个未访问点 p:
    N = getNeighbors(p)          ← O(n) 线性扫描
    IF |N| < minPts:
        标记 p 为 NOISE
    ELSE:
        创建新簇 C
        expandCluster(C, p, N):
            将 p 加入 C，标记 PART_OF_CLUSTER
            seeds = N
            FOR 每个 q in seeds (动态增长):
                IF q 未访问:
                    M = getNeighbors(q)      ← O(n)
                    IF |M| >= minPts:
                        seeds ∪= M           ← merge()
                IF q 不属于任何簇:
                    将 q 加入 C，标记 PART_OF_CLUSTER
        将 C 加入 clusters

RETURN clusters
```

## incdbscan原理

### 单点插入完整流程

```
Step 1: 将新点加入对象集                                    
  object_inserted = objects.insert_object(object_value)   
  ↓                                                        
  ├─ 计算 object_id = hash(object_value)                  
  ├─ 如果 object_id 已存在（重复点）:                      
  │   ├─ obj.count += 1                                   
  │   ├─ 对所有邻居: neighbor.neighbor_count += 1         
  │   └─ 返回 obj                                         
  └─ 如果 object_id 不存在（新点）:                        
      ├─ 创建新对象 new_object                             
      ├─ 加入图: node_id = graph.add_node(new_object)     
      ├─ 设置初始标签: UNCLASSIFIED (-2)                  
      ├─ 加入空间索引: neighbor_searcher.insert(...)      
      ├─ 更新邻居关系: _update_neighbors_during_insertion 
      └─ 返回 new_object                                  
                                                           
Step 2: 分类核心邻居                                        
  new_cores, old_cores =                                  
    _separate_core_neighbors_by_novelty(object_inserted)  
  ↓                                                        
  ├─ 遍历 object_inserted.neighbors                       
  ├─ if neighbor.neighbor_count == min_pts:               
  │   → 加入 new_cores (刚达到核心标准)                   
  ├─ elif neighbor.neighbor_count > min_pts:             
  │   → 加入 old_cores (之前就是核心)                     
  └─ 特殊处理: 如果 object_inserted 自身在 old_cores 中:  
      → 移到 new_cores (被插入对象视为新核心)             
                                                     
Step 3: 判断是否有新核心对象                                
  if not new_cores:  # 简单路径                          
    ├─ if old_cores:                                 
    │   → "吸收(Absorption)":                          
    │     label = max(old_cores的标签)                  
    │     objects.set_label(object_inserted, label)  
    └─ else:                                         
      → "噪声(Noise)":                                 
        objects.set_label(object_inserted, NOISE)    
    └─ 返回，流程结束                                     
  else:  # 复杂路径，继续 Step 4-7                        
                                                     
Step 4: 计算更新种子集                                      
  update_seeds = _get_update_seeds(new_cores)        
  ↓                                                  
  └─ seeds = ∪ { core_neighbor |                     
                 core_neighbor ∈ new_core.neighbors  
                 AND core_neighbor.is_core }         
  // 更新种子 = 所有新核心的核心邻居集合                    
                                                     
Step 5: 在更新种子中找连通分量                              
  components =                                       
    objects.get_connected_components_within_objects( 
      update_seeds)                                       
  ↓                                                        
  ├─ 提取子图: subgraph = graph.subgraph(node_ids)        
  ├─ 找连通分量: rx.connected_components(subgraph)        
  └─ 转换回 Object 集合                                   
                                                           
Step 6: 处理每个连通分量                                    
  for component in components:                            
    effective_labels = 获取component中有效标签             
    // 有效标签 = 排除 UNCLASSIFIED(-2) 和 NOISE(-1)      
                                                           
    if not effective_labels:                              
      → "创建(Creation)":                                 
        new_label = objects.get_next_cluster_label()      
        objects.set_labels(component, new_label)          
    else:                                                 
      → "吸收+合并(Absorption/Merge)":                    
        max_label = max(effective_labels)                 
        objects.set_labels(component, max_label)          
        for label in effective_labels:                    
          objects.change_labels(label, max_label)         
                                                           
Step 7: 更新新核心周围的边界/噪声对象                       
  _set_cluster_label_around_new_core_neighbors(new_cores) 
  ↓                                                       
  └─ for new_core in new_cores:                           
      label = objects.get_label(new_core)                 
      objects.set_labels(new_core.neighbors, label)       
      // 新核心的所有邻居继承其标签                         
```