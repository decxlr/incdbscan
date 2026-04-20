from typing import (
    Dict,
    List,
    Set
)

import rustworkx as rx

from ._labels import LabelHandler
from ._neighbor_searcher import NeighborSearcher
from ._object import (
    NodeId,
    Object,
    ObjectId
)
from ._utils import hash_


class Objects(LabelHandler):
    def __init__(self, eps, min_pts, metric, p):
        super().__init__()

        self.graph = rx.PyGraph(multigraph=False)  # pylint: disable=no-member
        self._object_id_to_node_id: Dict[ObjectId, NodeId] = {}

        self.neighbor_searcher = \
            NeighborSearcher(radius=eps, metric=metric, p=p)
        self.min_pts = min_pts

    # Reconstruct neighbor sets of objects during deserialization
    def __setstate__(self, state):
        self.__dict__.update(state)
        for node_id in self.graph.node_indices():
            obj = self.graph[node_id]
            obj.neighbors = {
                self.graph[nid]
                for nid in self.graph.neighbors(node_id)
            }
            obj.neighbors.add(obj)

    def get_object(self, value):
        object_id = hash_(value)
        if object_id in self._object_id_to_node_id:
            obj = self._get_object_from_object_id(object_id)
            return obj
        return None

    def insert_object(self, value):
        object_id = hash_(value)

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
        self._insert_graph_metadata(new_object)
        # 2. 设置初始标签
        self.set_label_of_inserted_object(new_object)
        # 3. 加入空间索引
        self.neighbor_searcher.insert(value, object_id)
        # 4. 更新邻居关系
        self._update_neighbors_during_insertion(new_object, value)
        return new_object

    def _insert_graph_metadata(self, new_object):
        node_id = self.graph.add_node(new_object)
        new_object.node_id = node_id
        object_id = new_object.id
        self._object_id_to_node_id[object_id] = node_id

    def _update_neighbors_during_insertion(self, object_inserted, new_value):
        # 1. 查询新点的ε邻居:返回所有与新点距离 ≤ eps 的已有对象ID
        neighbors = self._get_neighbors(new_value)
        # 2. 遍历每个邻居 obj:
        for obj in neighbors:
            # 新点成为obj的邻居
            obj.neighbor_count += 1
            # 不是自己
            if obj.id != object_inserted.id:
                object_inserted.neighbor_count += obj.count
                # 双向加入邻居集合
                obj.neighbors.add(object_inserted)
                object_inserted.neighbors.add(obj)
                self.graph.add_edge(object_inserted.node_id, obj.node_id, None)

    def _get_neighbors(self, query_value):
        neighbor_ids = self.neighbor_searcher.query_neighbors(query_value)

        for id_ in neighbor_ids:
            obj = self._get_object_from_object_id(id_)
            yield obj

    def _get_object_from_object_id(self, object_id):
        node_id = self._object_id_to_node_id[object_id]
        obj = self.graph[node_id]
        return obj

    def delete_object(self, obj):
        obj.count -= 1
        remove_from_data = obj.count == 0

        for neighbor in obj.neighbors:
            neighbor.neighbor_count -= 1
            if remove_from_data:
                if neighbor.id != obj.id:
                    neighbor.neighbors.remove(obj)

        if remove_from_data:
            self._delete_graph_metadata(obj)
            self.neighbor_searcher.delete(obj.id)
            self.delete_label_of_deleted_object(obj)

    def _delete_graph_metadata(self, deleted_object):
        node_id = deleted_object.node_id
        self.graph.remove_node(node_id)
        del self._object_id_to_node_id[deleted_object.id]

    def get_connected_components_within_objects(
            self, objects: Set[Object]) -> List[Set[Object]]:

        if len(objects) == 1:
            return [objects]

        node_ids = [obj.node_id for obj in objects]
        subgraph = self.graph.subgraph(node_ids)
        components_as_ids: List[Set[NodeId]] = rx.connected_components(subgraph)  # pylint: disable=no-member

        return [
            {subgraph[node_id] for node_id in component}
            for component in components_as_ids
        ]
