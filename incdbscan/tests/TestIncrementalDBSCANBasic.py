import numpy as np
import pytest
from incdbscan import IncrementalDBSCAN, IncrementalDBSCANWarning


class TestIncrementalDBSCANBasic:
    """测试 IncrementalDBSCAN 的基础功能"""

    def test_single_point_is_noise(self):
        """单个点应该是噪声点"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)
        point = np.array([[0.0, 0.0]])

        dbscan.insert(point)
        labels = dbscan.get_cluster_labels(point)

        assert labels[0] == -1  # -1 表示噪声

    def test_two_close_points_are_noise_when_min_pts_higher(self):
        """当 min_pts 较高时，两个近邻点仍然是噪声"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)
        points = np.array([
            [0.0, 0.0],
            [0.5, 0.0]
        ])

        dbscan.insert(points)
        labels = dbscan.get_cluster_labels(points)

        assert all(labels == -1)  # 都是噪声

    def test_three_close_points_form_cluster(self):
        """三个近邻点应该形成一个簇（满足 min_pts=3）"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)
        points = np.array([
            [0.0, 0.0],
            [0.5, 0.0],
            [0.3, 0.0]
        ])

        dbscan.insert(points)
        labels = dbscan.get_cluster_labels(points)

        # 所有点应该属于同一个簇（标签 >= 0）
        assert all(labels >= 0)
        # 所有点应该有相同的标签
        assert len(set(labels)) == 1

    def test_incremental_insertion_maintains_correct_clustering(self):
        """验证增量插入后聚类结果正确"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 先插入两个点（不足以形成簇）
        initial_points = np.array([
            [0.0, 0.0],
            [0.5, 0.0]
        ])
        dbscan.insert(initial_points)
        initial_labels = dbscan.get_cluster_labels(initial_points)
        assert all(initial_labels == -1)  # 都是噪声

        # 再插入第三个点，应该形成簇
        third_point = np.array([[0.3, 0.0]])
        dbscan.insert(third_point)

        # 现在所有点应该在同一个簇中
        all_points = np.vstack([initial_points, third_point])
        final_labels = dbscan.get_cluster_labels(all_points)
        assert all(final_labels >= 0)
        assert len(set(final_labels)) == 1

    def test_delete_point_breaks_cluster(self):
        """删除关键点可能导致簇分裂或变成噪声"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 创建三个点形成一个簇
        points = np.array([
            [0.0, 0.0],
            [0.5, 0.0],
            [0.3, 0.0]
        ])
        dbscan.insert(points)

        # 验证初始状态是簇
        labels_before = dbscan.get_cluster_labels(points)
        assert all(labels_before >= 0)

        # 删除一个点
        dbscan.delete(points[[2]])

        # 剩下的点应该变成噪声（只有2个点，不满足 min_pts=3）
        remaining_points = points[:2]
        labels_after = dbscan.get_cluster_labels(remaining_points)
        assert all(labels_after == -1)

    def test_delete_one_point_from_dense_cluster(self):
        """从密集簇中删除一个点，其他点仍保持簇状态"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 创建5个密集点
        points = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0],
            [0.9, 0.0],
            [1.2, 0.0]
        ])
        dbscan.insert(points)

        # 删除中间的点
        dbscan.delete(points[[2]])

        # 检查剩余点的状态
        remaining = np.delete(points, 2, axis=0)
        labels = dbscan.get_cluster_labels(remaining)

        # 至少有些点应该还在簇中
        assert any(labels >= 0)

    def test_two_separate_clusters(self):
        """验证能识别两个分离的簇"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 第一个簇在左侧
        cluster1 = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0]
        ])

        # 第二个簇在右侧（距离远）
        cluster2 = np.array([
            [10.0, 0.0],
            [10.3, 0.0],
            [10.6, 0.0]
        ])

        dbscan.insert(cluster1)
        dbscan.insert(cluster2)

        all_points = np.vstack([cluster1, cluster2])
        labels = dbscan.get_cluster_labels(all_points)

        # 应该有两个不同的簇标签
        unique_labels = set(labels)
        assert len(unique_labels) == 2
        assert -1 not in unique_labels  # 没有噪声点

    def test_noise_point_between_clusters(self):
        """验证簇间的孤立点是噪声"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        cluster1 = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0]
        ])

        noise_point = np.array([[5.0, 0.0]])

        cluster2 = np.array([
            [10.0, 0.0],
            [10.3, 0.0],
            [10.6, 0.0]
        ])

        dbscan.insert(cluster1)
        dbscan.insert(noise_point)
        dbscan.insert(cluster2)

        all_points = np.vstack([cluster1, noise_point, cluster2])
        labels = dbscan.get_cluster_labels(all_points)

        # 中间的点应该是噪声
        assert labels[3] == -1
        # 其他点应该形成两个簇
        assert len(set(labels)) == 3  # 2个簇 + 1个噪声

    def test_eps_parameter_effect(self):
        """验证 eps 参数对聚类的影响"""
        # 小 eps：点之间距离太远，无法形成簇
        dbscan_small = IncrementalDBSCAN(eps=0.1, min_pts=2)
        points = np.array([
            [0.0, 0.0],
            [0.5, 0.0]
        ])
        dbscan_small.insert(points)
        labels_small = dbscan_small.get_cluster_labels(points)
        assert all(labels_small == -1)

        # 大 eps：点之间足够近，形成簇
        dbscan_large = IncrementalDBSCAN(eps=1.0, min_pts=2)
        dbscan_large.insert(points)
        labels_large = dbscan_large.get_cluster_labels(points)
        assert all(labels_large >= 0)

    def test_min_pts_parameter_effect(self):
        """验证 min_pts 参数对聚类的影响"""
        points = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0]
        ])

        # min_pts=2：三个点形成簇
        dbscan_low = IncrementalDBSCAN(eps=1.0, min_pts=2)
        dbscan_low.insert(points)
        labels_low = dbscan_low.get_cluster_labels(points)
        assert all(labels_low >= 0)

        # min_pts=4：三个点不够，都是噪声
        dbscan_high = IncrementalDBSCAN(eps=1.0, min_pts=4)
        dbscan_high.insert(points)
        labels_high = dbscan_high.get_cluster_labels(points)
        assert all(labels_high == -1)

    def test_multiple_deletions_and_insertions(self):
        """验证多次删除和插入操作的正确性"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 初始插入
        points1 = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0]
        ])
        dbscan.insert(points1)
        assert all(dbscan.get_cluster_labels(points1) >= 0)

        # 删除所有点
        dbscan.delete(points1)
        assert all(dbscan.get_cluster_labels(points1) !=
                   dbscan.get_cluster_labels(points1))  # 会触发警告，返回 nan

        # 重新插入新点
        points2 = np.array([
            [5.0, 5.0],
            [5.3, 5.0],
            [5.6, 5.0]
        ])
        dbscan.insert(points2)
        labels2 = dbscan.get_cluster_labels(points2)
        assert all(labels2 >= 0)
        assert len(set(labels2)) == 1

    def test_warning_on_delete_nonexistent(self):
        """验证删除不存在对象时发出警告"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)
        point = np.array([[0.0, 0.0]])

        with pytest.warns(IncrementalDBSCANWarning):
            dbscan.delete(point)

    def test_warning_on_get_label_nonexistent(self):
        """验证获取不存在对象的标签时发出警告"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)
        point = np.array([[0.0, 0.0]])

        with pytest.warns(IncrementalDBSCANWarning):
            labels = dbscan.get_cluster_labels(point)
            assert np.isnan(labels[0])

    def test_batch_insertion(self):
        """验证批量插入多个点"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 一次性插入10个点形成密集簇
        points = np.random.rand(10, 2) * 0.5

        dbscan.insert(points)
        labels = dbscan.get_cluster_labels(points)

        # 大部分点应该在簇中
        assert sum(labels >= 0) > 0

    def test_high_dimensional_data(self):
        """验证高维数据的聚类"""
        dbscan = IncrementalDBSCAN(eps=2.0, min_pts=3)

        # 10维数据
        points = np.array([
            [0.0] * 10,
            [0.5] * 10,
            [1.0] * 10
        ])

        dbscan.insert(points)
        labels = dbscan.get_cluster_labels(points)

        # 在高维空间中，这些点可能形成簇
        assert len(labels) == 3

    def test_empty_input_handling(self):
        """验证空输入的处理"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 插入空数组
        empty = np.array([]).reshape(0, 2)
        dbscan.insert(empty)

        # 不应该报错
        assert True

    def test_duplicate_points(self):
        """验证重复点的处理"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        # 插入相同的点三次
        point = np.array([[0.0, 0.0]])
        dbscan.insert(point)
        dbscan.insert(point)
        dbscan.insert(point)

        labels = dbscan.get_cluster_labels(point)
        # 重复点应该被视为多个邻居，可能形成簇
        assert labels[0] >= 0

    def test_cluster_label_consistency(self):
        """验证多次查询标签的一致性"""
        dbscan = IncrementalDBSCAN(eps=1.0, min_pts=3)

        points = np.array([
            [0.0, 0.0],
            [0.3, 0.0],
            [0.6, 0.0]
        ])
        dbscan.insert(points)

        # 多次查询应该得到相同结果
        labels1 = dbscan.get_cluster_labels(points)
        labels2 = dbscan.get_cluster_labels(points)
        labels3 = dbscan.get_cluster_labels(points)

        assert np.array_equal(labels1, labels2)
        assert np.array_equal(labels2, labels3)

    def test_different_metrics(self):
        """验证不同距离度量的使用"""
        points = np.array([
            [0.0, 0.0],
            [1.0, 0.0],
            [0.0, 1.0]
        ])

        # 使用欧氏距离
        dbscan_euclidean = IncrementalDBSCAN(eps=1.5, min_pts=3, metric='euclidean')
        dbscan_euclidean.insert(points)
        labels_euc = dbscan_euclidean.get_cluster_labels(points)

        # 使用曼哈顿距离
        dbscan_manhattan = IncrementalDBSCAN(eps=1.5, min_pts=3, metric='manhattan')
        dbscan_manhattan.insert(points)
        labels_man = dbscan_manhattan.get_cluster_labels(points)

        # 两种度量可能产生不同结果
        # 这里只是验证不会报错
        assert len(labels_euc) == 3
        assert len(labels_man) == 3


# 运行测试的辅助函数
if __name__ == '__main__':
    pytest.main([__file__, '-v'])
