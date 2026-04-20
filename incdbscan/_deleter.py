from collections import defaultdict

from ._bfscomponentfinder import BFSComponentFinder
from ._labels import CLUSTER_LABEL_NOISE


class Deleter:
    def __init__(self, eps, min_pts, objects):
        self.eps = eps
        self.min_pts = min_pts
        self.objects = objects

    def delete(self, object_to_delete):
        # 步骤1：删除对象并更新邻域关系
        self.objects.delete_object(object_to_delete)
        object_deleted = object_to_delete

        # 步骤2：找出失去核心属性的对象（Ex-Cores）
        ex_cores = self._get_objects_that_lost_core_property(object_deleted)

        # 步骤3：获取更新种子和非核心邻居
        update_seeds, non_core_neighbors_of_ex_cores = \
            self._get_update_seeds_and_non_core_neighbors_of_ex_cores(
                ex_cores, object_deleted)

        # 步骤4：处理可能的簇分裂
        if update_seeds:
            # Only for update seeds belonging to the same cluster do we
            # have to consider if split is needed.

            # 按簇分组更新种子
            update_seeds_by_cluster = \
                self._group_objects_by_cluster(update_seeds)

            for seeds in update_seeds_by_cluster.values():
                # 查找需要分裂出去的连通分量
                components = self._find_components_to_split_away(seeds)
                for component in components:
                    # 分配新标签
                    self.objects.set_labels(
                        component, self.objects.get_next_cluster_label())

        # Updating labels of border objects that were in the neighborhood
        # of objects that lost their core property is always needed. They
        # become either borders of other clusters or noise.

        # 步骤5：更新边界对象的标签
        self._set_each_border_object_labels_to_largest_around(
            non_core_neighbors_of_ex_cores)

    def _get_objects_that_lost_core_property(self, object_deleted):
        threshold = self.min_pts - 1
        for obj in object_deleted.neighbors:
            if obj.neighbor_count == threshold:
                yield obj

        # The result has to contain the deleted object if it was core
        if object_deleted.is_core:
            yield object_deleted

    def _get_update_seeds_and_non_core_neighbors_of_ex_cores(
            self,
            ex_cores,
            object_deleted):

        update_seeds = set()
        non_core_neighbors_of_ex_cores = set()

        for ex_core in ex_cores:
            for neighbor in ex_core.neighbors:
                if neighbor.is_core:
                    update_seeds.add(neighbor)
                else:
                    non_core_neighbors_of_ex_cores.add(neighbor)

        if object_deleted.count == 0:
            update_seeds = update_seeds.difference({object_deleted})
            non_core_neighbors_of_ex_cores = \
                non_core_neighbors_of_ex_cores.difference({object_deleted})

        return update_seeds, non_core_neighbors_of_ex_cores

    def _group_objects_by_cluster(self, objects):
        grouped_objects = defaultdict(list)

        for obj in objects:
            label = self.objects.get_label(obj)
            grouped_objects[label].append(obj)

        return grouped_objects

    def _find_components_to_split_away(self, seed_objects):
        if len(seed_objects) == 1:
            return []

        if self._objects_are_neighbors_of_each_other(seed_objects):
            return []

        finder = BFSComponentFinder(self.objects.graph)
        seed_node_ids = [obj.node_id for obj in seed_objects]
        components = finder.find_components(seed_node_ids)
        return components

    @staticmethod
    def _objects_are_neighbors_of_each_other(objects):
        for obj1 in objects:
            for obj2 in objects:
                if obj2 not in obj1.neighbors:
                    return False
        return True

    def _set_each_border_object_labels_to_largest_around(self, objects_to_set):
        cluster_updates = {}

        for obj in objects_to_set:
            labels = self._get_cluster_labels_in_neighborhood(obj)
            if not labels:
                labels.add(CLUSTER_LABEL_NOISE)

            cluster_updates[obj] = max(labels)

        for obj, new_cluster_label in cluster_updates.items():
            self.objects.set_label(obj, new_cluster_label)

    def _get_cluster_labels_in_neighborhood(self, obj):
        return {self.objects.get_label(neighbor)
                for neighbor in obj.neighbors
                if neighbor.is_core}
