/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.math4.legacy.ml.clustering;

import org.apache.commons.math4.legacy.exception.NotPositiveException;
import org.apache.commons.math4.legacy.exception.NullArgumentException;
import org.apache.commons.math4.legacy.ml.distance.DistanceMeasure;
import org.apache.commons.math4.legacy.ml.distance.EuclideanDistance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Incremental DBSCAN (Density-Based Spatial Clustering of Applications with Noise) algorithm.
 *
 * <p>Unlike {@link DBSCANClusterer} which recomputes the entire clustering from scratch on
 * each call to {@link #cluster(Collection)}, this implementation maintains persistent state
 * and supports efficient incremental updates via {@link #addPoint(Clusterable)} and
 * {@link #removePoint(Clusterable)}.
 *
 * <p>Only the ε-neighborhood of the changed point requires re-evaluation, making each
 * individual operation O(k) where k is the size of the local neighborhood, rather than
 * O(n) for a full scan.
 *
 * <p><b>Insertion cases</b> (following Ester et al. 1998 incremental extension):
 * <ul>
 *   <li><b>Noise</b> – new point has no core neighbor → assigned as noise.</li>
 *   <li><b>Absorption</b> – new point has existing core neighbors but no new core is
 *       created → joins the cluster of the highest-labeled core neighbor.</li>
 *   <li><b>Creation</b> – insertion triggers new core point(s) with no existing cluster
 *       nearby → a new cluster is created.</li>
 *   <li><b>Merge</b> – new core point(s) bridge multiple existing clusters → merged into
 *       one cluster (highest label wins).</li>
 * </ul>
 *
 * <p><b>Deletion</b> handles potential cluster splits: when a point is removed, the
 * connectivity of its former core neighbors is re-evaluated using BFS traversal along
 * core-point edges; disconnected sub-groups receive fresh cluster labels.
 *
 * <p><b>Cluster label semantics</b>:
 * <ul>
 *   <li>{@link #LABEL_NOISE} ({@code -1}) – the point is classified as noise.</li>
 *   <li>Any non-negative integer – the point belongs to that cluster.
 *       Labels are monotonically increasing and are never reused.</li>
 * </ul>
 *
 * <p><b>Usage</b>:
 * <pre>{@code
 * IncrementalDBSCANClusterer<DoublePoint> clusterer =
 *     new IncrementalDBSCANClusterer<>(2.0, 3);
 *
 * // Incremental additions
 * for (DoublePoint p : stream) {
 *     clusterer.addPoint(p);
 * }
 * List<Cluster<DoublePoint>> clusters = clusterer.getClusters();
 *
 * // Remove a point
 * clusterer.removePoint(somePoint);
 *
 * // Also usable as a standard batch clusterer
 * List<Cluster<DoublePoint>> result = clusterer.cluster(allPoints);
 * }</pre>
 *
 * @param <T> type of the points to cluster
 * @see DBSCANClusterer
 */
public class IncrementalDBSCANClustererV1<T extends Clusterable> extends Clusterer<T> {

    /** Cluster label assigned to noise points. */
    private static final int LABEL_NOISE = -1;

    /**
     * Internal sentinel label for a point that has been inserted but whose cluster
     * assignment has not yet been determined.
     */
    private static final int LABEL_UNCLASSIFIED = -2;

    /** Maximum radius of the ε-neighborhood. */
    private final double eps;

    /**
     * Minimum number of points within ε (including the point itself) required for
     * a point to be a core point.
     */
    private final int minPts;

    /** Next cluster label to assign; incremented each time a new cluster is created. */
    private int nextLabel = 0;

    /**
     * Active points, mapped to their internal metadata. Insertion order is preserved
     * for deterministic iteration.
     */
    private final Map<T, PointNode> nodes = new LinkedHashMap<>();

    // =========================================================================
    // Inner class: PointNode
    // =========================================================================

    /**
     * Internal per-point metadata maintained across incremental updates.
     *
     * <p>{@link #neighborCount} counts all points within ε distance including the
     * point itself.  A point is a <em>core point</em> when
     * {@code neighborCount >= minPts}.
     *
     * <p>{@link #neighbors} is the live adjacency set (including {@code this}).
     * Keeping this set avoids re-scanning all points on every update.
     */
    final class PointNode {
        /** The wrapped clusterable point. */
        final T point;

        /**
         * All PointNodes (including {@code this}) whose distance to {@code point}
         * is at most ε. Kept in sync when points are added or removed.
         */
        final Set<PointNode> neighbors = new HashSet<>();

        /**
         * Number of points within ε of {@code point}, counting itself.
         * Invariant: {@code neighborCount == neighbors.size()} for datasets
         * without exact duplicates.
         */
        int neighborCount;

        /**
         * Current cluster label: a non-negative integer for real clusters,
         * {@link #LABEL_NOISE}, or {@link #LABEL_UNCLASSIFIED}.
         */
        int label;

        PointNode(final T point) {
            this.point = point;
            this.label = LABEL_UNCLASSIFIED;
            this.neighbors.add(this);   // self is always a neighbor
            this.neighborCount = 1;     // count self
        }

        /** Returns {@code true} if this point has at least {@code minPts} neighbors. */
        boolean isCore() {
            return neighborCount >= minPts;
        }

        @Override
        public String toString() {
            return "PointNode{label=" + label + ", neighborCount=" + neighborCount + "}";
        }
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a new IncrementalDBSCANClusterer using Euclidean distance.
     *
     * @param eps    maximum radius of the ε-neighborhood (must be ≥ 0)
     * @param minPts minimum number of points (including self) to form a core point
     *               (must be ≥ 0)
     * @throws NotPositiveException if {@code eps < 0.0} or {@code minPts < 0}
     */
    public IncrementalDBSCANClustererV1(final double eps, final int minPts) {
        this(eps, minPts, new EuclideanDistance());
    }

    /**
     * Creates a new IncrementalDBSCANClusterer with a custom distance measure.
     *
     * @param eps     maximum radius of the ε-neighborhood (must be ≥ 0)
     * @param minPts  minimum number of points (including self) to form a core point
     *                (must be ≥ 0)
     * @param measure the distance measure to use
     * @throws NotPositiveException if {@code eps < 0.0} or {@code minPts < 0}
     */
    public IncrementalDBSCANClustererV1(final double eps, final int minPts,
                                        final DistanceMeasure measure) {
        super(measure);
        if (eps < 0.0d) {
            throw new NotPositiveException(eps);
        }
        if (minPts < 0) {
            throw new NotPositiveException(minPts);
        }
        this.eps = eps;
        this.minPts = minPts;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the maximum radius of the ε-neighborhood.
     *
     * @return ε value
     */
    public double getEps() {
        return eps;
    }

    /**
     * Returns the minimum number of points required to form a core point.
     *
     * @return minPts value
     */
    public int getMinPts() {
        return minPts;
    }

    /**
     * Performs batch DBSCAN clustering, satisfying the {@link Clusterer} contract.
     *
     * <p>Clears all existing incremental state, then inserts each point in iteration
     * order.  This is equivalent to calling {@link #reset()} followed by
     * {@link #addPoint(Clusterable)} for every point.
     *
     * @param points points to cluster (cannot be {@code null})
     * @return the list of non-noise clusters
     */
    @Override
    public List<Cluster<T>> cluster(final Collection<T> points) {
        NullArgumentException.check(points);
        reset();
        for (final T point : points) {
            addPoint(point);
        }
        return getClusters();
    }

    /**
     * Clears all incremental state.  Equivalent to constructing a fresh instance
     * with the same parameters.
     */
    public void reset() {
        nodes.clear();
        nextLabel = 0;
    }

    /**
     * Incrementally inserts a point and updates the clustering.
     *
     * <p>If a point that is {@code equal} to {@code point} is already present,
     * this call is a no-op.
     *
     * @param point the point to insert (cannot be {@code null})
     */
    public void addPoint(final T point) {
        NullArgumentException.check(point);
        if (nodes.containsKey(point)) {
            return;
        }
        final PointNode newNode = new PointNode(point);
        nodes.put(point, newNode);
        linkNeighbors(newNode);
        processInsertion(newNode);
    }

    /**
     * Incrementally removes a point and updates the clustering.
     *
     * <p>If the point is not present, this call is a no-op.
     *
     * @param point the point to remove (cannot be {@code null})
     */
    public void removePoint(final T point) {
        NullArgumentException.check(point);
        final PointNode toDelete = nodes.get(point);
        if (toDelete == null) {
            return;
        }
        processDeletion(toDelete);
        // Physically unlink the node after cluster bookkeeping is complete.
        for (final PointNode neighbor : toDelete.neighbors) {
            if (neighbor != toDelete) {
                neighbor.neighbors.remove(toDelete);
                neighbor.neighborCount--;
            }
        }
        nodes.remove(point);
    }

    /**
     * Returns a snapshot of the current clustering.  Noise points are excluded.
     *
     * @return list of clusters (may be empty)
     */
    public List<Cluster<T>> getClusters() {
        final Map<Integer, Cluster<T>> clusterMap = new LinkedHashMap<>();
        for (final PointNode node : nodes.values()) {
            if (node.label >= 0) {
                clusterMap.computeIfAbsent(node.label, id -> new Cluster<>())
                          .addPoint(node.point);
            }
        }
        return new ArrayList<>(clusterMap.values());
    }

    /**
     * Returns the current cluster label for a point.
     *
     * @param point the point to query
     * @return cluster label, or {@link #LABEL_NOISE} if the point is noise or
     *         not present in this clusterer
     */
    public int getLabel(final T point) {
        final PointNode node = nodes.get(point);
        return node != null ? node.label : LABEL_NOISE;
    }

    /**
     * Returns the number of points currently managed by this clusterer.
     *
     * @return active point count
     */
    public int size() {
        return nodes.size();
    }

    // =========================================================================
    // Neighbor linking
    // =========================================================================

    /**
     * Scans all existing nodes for ε-neighbors of {@code newNode} and creates
     * bidirectional links, updating {@link PointNode#neighborCount} on both sides.
     */
    private void linkNeighbors(final PointNode newNode) {
        for (final PointNode existing : nodes.values()) {
            if (existing == newNode) {
                continue;
            }
            if (distance(existing.point, newNode.point) <= eps) {
                newNode.neighbors.add(existing);
                newNode.neighborCount++;
                existing.neighbors.add(newNode);
                existing.neighborCount++;
            }
        }
    }

    // =========================================================================
    // Insertion logic
    // =========================================================================

    /**
     * Determines the cluster assignment for {@code inserted} and updates all
     * affected points.
     *
     * <h3>Algorithm outline</h3>
     * <ol>
     *   <li>Classify each neighbor of {@code inserted} as a <em>new core</em>
     *       (just reached {@code minPts} because of this insertion) or an
     *       <em>old core</em> (was already core before).</li>
     *   <li>If no new cores exist: Noise or Absorption.</li>
     *   <li>Otherwise: collect <em>update seeds</em> (all core neighbors of every
     *       new core), find connected components of the induced subgraph, and
     *       create / merge clusters as appropriate.</li>
     *   <li>Propagate labels from new cores to their non-core (border) neighbors.</li>
     * </ol>
     */
    private void processInsertion(final PointNode inserted) {
        final Set<PointNode> newCores = new HashSet<>();
        final Set<PointNode> oldCores = new HashSet<>();

        for (final PointNode neighbor : inserted.neighbors) {
            if (neighbor == inserted) {
                continue;
            }
            if (neighbor.neighborCount == minPts) {
                // Just reached the core threshold due to this insertion.
                newCores.add(neighbor);
            } else if (neighbor.neighborCount > minPts) {
                // Was already a core point before this insertion.
                oldCores.add(neighbor);
            }
        }

        // The inserted node itself cannot have been a core before; if it is core
        // now it is always classified as a new core.
        if (inserted.isCore()) {
            oldCores.remove(inserted);
            newCores.add(inserted);
        }

        if (newCores.isEmpty()) {
            // ---- Noise or Absorption ----
            if (!oldCores.isEmpty()) {
                // Absorption: border point joins the most recently created cluster.
                int maxLabel = LABEL_NOISE;
                for (final PointNode oc : oldCores) {
                    if (oc.label > maxLabel) {
                        maxLabel = oc.label;
                    }
                }
                inserted.label = maxLabel;
            } else {
                // Noise: no core neighbors at all.
                inserted.label = LABEL_NOISE;
            }
            return;
        }

        // ---- Creation or Merge ----

        // Update seeds = all core neighbors (including self) of every new core.
        final Set<PointNode> updateSeeds = new HashSet<>();
        for (final PointNode nc : newCores) {
            for (final PointNode neighbor : nc.neighbors) {
                if (neighbor.isCore()) {
                    updateSeeds.add(neighbor);
                }
            }
        }

        // Find connected components of the subgraph induced by updateSeeds.
        // Two seeds are directly connected if they are mutual ε-neighbors.
        final List<Set<PointNode>> components = connectedComponentsOf(updateSeeds);

        for (final Set<PointNode> component : components) {
            // Collect the non-special (real) cluster labels in this component.
            final Set<Integer> effectiveLabels = new HashSet<>();
            for (final PointNode n : component) {
                if (n.label >= 0) {
                    effectiveLabels.add(n.label);
                }
            }

            if (effectiveLabels.isEmpty()) {
                // Creation: no existing cluster is involved; create a new one.
                final int newLabel = nextLabel++;
                setLabels(component, newLabel);
            } else {
                // Absorption / Merge: unify all participating clusters under
                // the highest existing label.
                final int maxLabel = Collections.max(effectiveLabels);
                for (final int oldLabel : effectiveLabels) {
                    if (oldLabel != maxLabel) {
                        changeLabels(oldLabel, maxLabel);
                    }
                }
                setLabels(component, maxLabel);
            }
        }

        // Propagate the label of each new core to its unclassified / noise neighbors.
        propagateAroundNewCores(newCores);
    }

    // =========================================================================
    // Deletion logic
    // =========================================================================

    /**
     * Updates cluster assignments when {@code toDelete} is about to be removed.
     *
     * <h3>Algorithm outline</h3>
     * <ol>
     *   <li>Decrement {@link PointNode#neighborCount} for all neighbors of
     *       {@code toDelete} (simulating removal).</li>
     *   <li>Identify <em>ex-cores</em>: nodes that have just lost core status.</li>
     *   <li>Collect <em>update seeds</em> (core neighbors of ex-cores, excluding
     *       {@code toDelete}) and non-core neighbors of ex-cores.</li>
     *   <li>For each cluster-group of update seeds, run BFS split detection and
     *       assign fresh labels to any disconnected sub-components.</li>
     *   <li>Re-evaluate non-core (border) neighbors: each one either inherits the
     *       label of a remaining core neighbor or becomes noise.</li>
     * </ol>
     *
     * <p>Physical removal of the node from the data structure happens in
     * {@link #removePoint(Clusterable)} after this method returns.
     */
    private void processDeletion(final PointNode toDelete) {
        // Step 1: decrement neighbor counts to simulate removal.
        for (final PointNode neighbor : toDelete.neighbors) {
            neighbor.neighborCount--;
        }

        // Step 2: find ex-cores — nodes that have just lost core status.
        // After decrement: neighborCount == minPts - 1  ⟺  was exactly at threshold.
        // Special case: toDelete itself — was core if (decremented count + 1) >= minPts.
        final Set<PointNode> exCores = new HashSet<>();
        for (final PointNode neighbor : toDelete.neighbors) {
            if (neighbor == toDelete) {
                continue;
            }
            if (neighbor.neighborCount == minPts - 1) {
                exCores.add(neighbor);
            }
        }
        if (toDelete.neighborCount + 1 >= minPts) {
            // toDelete was a core point; add it to exCores for connectivity analysis.
            exCores.add(toDelete);
        }

        // Step 3: collect update seeds and non-core neighbors from ex-cores.
        final Set<PointNode> updateSeeds = new HashSet<>();
        final Set<PointNode> nonCoreNeighbors = new HashSet<>();

        for (final PointNode exCore : exCores) {
            for (final PointNode neighbor : exCore.neighbors) {
                if (neighbor == toDelete) {
                    continue;
                }
                if (neighbor.isCore()) {
                    updateSeeds.add(neighbor);
                } else {
                    nonCoreNeighbors.add(neighbor);
                }
            }
        }
        updateSeeds.remove(toDelete);
        nonCoreNeighbors.remove(toDelete);

        // Step 4: split detection — within each cluster group of update seeds,
        // check whether removing toDelete disconnects the core-point graph.
        if (!updateSeeds.isEmpty()) {
            final Map<Integer, List<PointNode>> seedsByCluster = new HashMap<>();
            for (final PointNode seed : updateSeeds) {
                seedsByCluster.computeIfAbsent(seed.label, k -> new ArrayList<>()).add(seed);
            }

            for (final List<PointNode> clusterSeeds : seedsByCluster.values()) {
                final List<Set<PointNode>> splitComponents =
                        findSplitComponents(clusterSeeds, toDelete);
                for (final Set<PointNode> component : splitComponents) {
                    final int newLabel = nextLabel++;
                    setLabels(component, newLabel);
                }
            }
        }

        // Step 5: re-evaluate non-core (border) neighbors.
        for (final PointNode border : nonCoreNeighbors) {
            if (border == toDelete) {
                continue;
            }
            int maxLabel = LABEL_NOISE;
            for (final PointNode neighbor : border.neighbors) {
                if (neighbor != toDelete && neighbor.isCore() && neighbor.label > maxLabel) {
                    maxLabel = neighbor.label;
                }
            }
            border.label = maxLabel;
        }
    }

    // =========================================================================
    // BFS split detection
    // =========================================================================

    /**
     * Determines which sub-components of {@code seeds} become disconnected after
     * removing {@code excluded}.
     *
     * <p>All seeds belong to the same cluster.  BFS traversal proceeds along
     * core-point edges only (non-core points are not traversal waypoints, matching
     * the density-connectivity definition).  The largest component retains its
     * original label; the remaining ones are returned here for re-labelling.
     *
     * @param seeds    core-point update seeds, all in the same cluster
     * @param excluded the node being deleted (ignored during BFS)
     * @return components to split away (each will receive a new label); may be empty
     */
    private List<Set<PointNode>> findSplitComponents(final List<PointNode> seeds,
                                                      final PointNode excluded) {
        if (seeds.size() <= 1) {
            return Collections.emptyList();
        }
        // Fast path: if every pair of seeds is a mutual neighbor, they form a single
        // component regardless of the deletion.
        if (allMutualNeighbors(seeds)) {
            return Collections.emptyList();
        }

        // BFS from all seeds simultaneously.  Track which "origin seed" first
        // reached each node, and merge component entries when two seeds meet.
        final Map<PointNode, Integer> assignment = new HashMap<>();  // node → seed index
        final Map<Integer, Set<PointNode>> components = new HashMap<>();

        for (int i = 0; i < seeds.size(); i++) {
            assignment.put(seeds.get(i), i);
            final Set<PointNode> comp = new HashSet<>();
            comp.add(seeds.get(i));
            components.put(i, comp);
        }

        final Set<PointNode> visited = new HashSet<>(seeds);
        final Deque<PointNode> queue = new ArrayDeque<>(seeds);

        while (!queue.isEmpty()) {
            final PointNode current = queue.poll();
            if (!current.isCore() || current == excluded) {
                continue;
            }
            final int currentIdx = assignment.get(current);

            for (final PointNode neighbor : current.neighbors) {
                if (neighbor == excluded || !neighbor.isCore()) {
                    continue;
                }
                if (!visited.contains(neighbor)) {
                    // Tree edge: neighbor is newly discovered.
                    visited.add(neighbor);
                    assignment.put(neighbor, currentIdx);
                    components.get(currentIdx).add(neighbor);
                    queue.add(neighbor);
                } else {
                    // Cross/back edge: two components may have just connected.
                    final int neighborIdx = assignment.get(neighbor);
                    if (neighborIdx != currentIdx) {
                        mergeComponentMaps(components, assignment, neighborIdx, currentIdx);
                    }
                }
            }
        }

        if (components.size() <= 1) {
            return Collections.emptyList();
        }

        // Sort descending by size; keep the largest, return the rest for re-labelling.
        final List<Set<PointNode>> allComps = new ArrayList<>(components.values());
        allComps.sort((a, b) -> b.size() - a.size());
        allComps.remove(0);  // keep largest (retains original label)
        return allComps;
    }

    /**
     * Merges the component at {@code fromIdx} into the one at {@code intoIdx},
     * updating both {@code components} and {@code assignment} maps.
     */
    private void mergeComponentMaps(final Map<Integer, Set<PointNode>> components,
                                     final Map<PointNode, Integer> assignment,
                                     final int fromIdx, final int intoIdx) {
        final Set<PointNode> from = components.remove(fromIdx);
        if (from == null) {
            return;
        }
        final Set<PointNode> into = components.get(intoIdx);
        for (final PointNode node : from) {
            assignment.put(node, intoIdx);
        }
        into.addAll(from);
    }

    /**
     * Returns {@code true} if every pair of nodes in the list are mutual
     * ε-neighbors (i.e., each appears in the other's {@link PointNode#neighbors}).
     */
    private boolean allMutualNeighbors(final List<PointNode> nodeList) {
        for (int i = 0; i < nodeList.size(); i++) {
            for (int j = i + 1; j < nodeList.size(); j++) {
                if (!nodeList.get(i).neighbors.contains(nodeList.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    // =========================================================================
    // Connected components (subgraph of update seeds, used during insertion)
    // =========================================================================

    /**
     * Finds connected components of the subgraph induced by {@code objects}.
     * Two nodes are in the same component iff they are connected through a path
     * of mutual ε-neighbors, all of which must be members of {@code objects}.
     *
     * @param objects set of nodes to partition into components
     * @return list of non-empty components (partitions of {@code objects})
     */
    private List<Set<PointNode>> connectedComponentsOf(final Set<PointNode> objects) {
        if (objects.size() == 1) {
            return Collections.singletonList(new HashSet<>(objects));
        }

        final Set<PointNode> unvisited = new HashSet<>(objects);
        final List<Set<PointNode>> result = new ArrayList<>();

        while (!unvisited.isEmpty()) {
            final PointNode start = unvisited.iterator().next();
            final Set<PointNode> component = new HashSet<>();
            final Deque<PointNode> queue = new ArrayDeque<>();
            queue.add(start);
            unvisited.remove(start);

            while (!queue.isEmpty()) {
                final PointNode current = queue.poll();
                component.add(current);
                for (final PointNode neighbor : current.neighbors) {
                    if (unvisited.contains(neighbor)) {
                        unvisited.remove(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            result.add(component);
        }
        return result;
    }

    // =========================================================================
    // Label helpers
    // =========================================================================

    /** Sets {@code label} on every node in {@code nodeSet}. */
    private void setLabels(final Set<PointNode> nodeSet, final int label) {
        for (final PointNode node : nodeSet) {
            node.label = label;
        }
    }

    /** Renames all occurrences of {@code oldLabel} to {@code newLabel} globally. */
    private void changeLabels(final int oldLabel, final int newLabel) {
        for (final PointNode node : nodes.values()) {
            if (node.label == oldLabel) {
                node.label = newLabel;
            }
        }
    }

    /**
     * For each node in {@code newCores}, assigns that core's cluster label to any
     * neighboring node whose label is currently {@link #LABEL_NOISE} or
     * {@link #LABEL_UNCLASSIFIED} (i.e., border points and the just-inserted node).
     */
    private void propagateAroundNewCores(final Set<PointNode> newCores) {
        for (final PointNode core : newCores) {
            final int label = core.label;
            for (final PointNode neighbor : core.neighbors) {
                if (neighbor.label < 0) {
                    neighbor.label = label;
                }
            }
        }
    }
}
