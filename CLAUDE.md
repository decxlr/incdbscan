# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Install dependencies
poetry install

# Run tests (excluding slow tests)
make test
# Or directly:
python -m pytest -m "not slow" incdbscan/tests/

# Run slow tests only
make test-slow

# Run a single test file
python -m pytest incdbscan/tests/test_inserter.py

# Lint
make lint        # pylint
make isort       # fix import order
make isort-check # check import order without fixing
```

## Architecture

**IncrementalDBSCAN** is an incremental variant of DBSCAN clustering: given an existing clustering, it efficiently
updates it when points are inserted or deleted rather than recomputing from scratch.

Public API (`incdbscan/__init__.py`): `IncrementalDBSCAN` and `IncrementalDBSCANWarning`.

The main class (`incrementaldbscan.py`) delegates to two internal handler classes:

- `_Inserter` — handles point insertion, updating cluster membership
- `_Deleter` — handles point deletion, potentially splitting or dissolving clusters

Shared infrastructure used by both:

- `_NeighborSearcher` — finds ε-neighbors using scikit-learn's ball tree / KD-tree
- `_BFSComponentFinder` — finds connected components in the neighbor graph via BFS (uses `rustworkx`)
- `_Objects` / `_Object` — stores the point set and per-point metadata
- `_Labels` — manages cluster label assignment and merging
- `_Utils` — input validation helpers

The algorithm aims to produce the same result as running full DBSCAN after each insertion/deletion, but only touches the
neighborhood of the changed point.

## Testing

Tests live in `incdbscan/tests/`. Slow tests are marked with `@pytest.mark.slow`. The `testutils` package (inside the
tests directory) provides shared fixtures and helpers — isort treats it as first-party.

## 注意事项

- 你的所有回答使用中文输出
- 你的最终执行结果或者结论输出到一个结果文档中,markdown格式
- 输出文档目录为analysis(E:\work\OpenNMS\incdbscan\incdbscan\analysis),你的所有输出文档都放在这个目录下
- 输出尽可能准确详细