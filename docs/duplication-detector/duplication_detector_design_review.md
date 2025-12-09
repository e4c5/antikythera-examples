# Duplication Detector Design Review

**Document version reviewed:** `duplication_detector_design.md` (v2.0, Dec 9 2025)  
**Reviewer:** GitHub Copilot  
**Date:** Dec 9 2025

---

## 1. Executive Summary

The document proposes a hybrid LCS + Levenshtein + structural approach with rich metadata capture to unlock automated refactoring. The vision is compelling, but the plan under-specifies how the algorithms and data structures will scale and be validated. Key risks concentrate around quadratic comparison costs, heavy per-token metadata, and unproven feasibility checks for automated rewrites. Competitive differentiation vs. CPD, SonarQube, CloneDR, or NiCad also needs sharper articulation.

---

## 2. Algorithm & Data-Structure Assessment

| Area | Observation | Risk | Recommendation |
|------|-------------|------|----------------|
| **Sequence generation** | Sliding-window extraction is mentioned but not concretely defined (window sizing, stride, overlap, pruning). No mention of suffix arrays/trees, hashing, or shingles to pre-cluster candidates before expensive comparisons. | High runtime (>O(N²)) and memory blow-up when analyzing large monorepos (>10k windows). | Specify window derivation rules, add token hashes (w-shingling, MinHash/LSH) or suffix arrays to eliminate most pairs before LCS/Levenshtein. |
| **Similarity stack** | LCS + Levenshtein run per pair. Both are O(n·m); together they double work without sharing DP tables. | High CPU cost, especially because lengths can reach dozens of tokens. | Evaluate whether Levenshtein adds signal beyond LCS; consider reusing DP matrices or replacing one metric with cheaper n-gram cosine similarity for the first pass. |
| **Structural signature** | Signature tracks shallow metrics (control-flow tokens, depth, call counts) but lacks AST sub-tree hashing or CFG serialization. | False negatives (semantic clones rearranged structurally) and false positives (same control skeleton, different semantics). | Incorporate subtree hashes (e.g., Deckard-style characteristic vectors) or lightweight embeddings; treat control-flow shape as heuristic, not decisive filter. |
| **Enhanced Token** | Record stores AST node references, compilation units, inferred types per token. No lifecycle plan beyond “GC handles it.” | High per-token memory, risk of OOM on large projects, GC pressure from retaining AST fragments. | Introduce lightweight IDs + offset tracking, use weak refs or detach heavy AST objects after metadata extraction. |
| **StatementSequence** | Retains entire `CompilationUnit`, `ClassDeclaration`, original source, imports, scope context for each window. | Duplicate storage of large structures; multiple sequences from same file copy identical data. | Deduplicate via flyweight handles or IDs referencing shared per-file context objects. |
| **Scope context** | Doesn’t describe handling of nested classes, anonymous classes, lambdas, or cross-language sources (Kotlin/Scala). | Incorrect scope mapping → invalid refactoring suggestions. | Enumerate scope walkers for edge cases; define fallbacks when context is ambiguous. |
| **Variation tracking** | Tracks position-based diffs but relies on positional alignment even when sequences drift. | Drift after normalization could misalign variations, producing wrong parameter inference. | Base variation mapping on alignment output from similarity algorithm; store token indices from both sequences. |
| **Feasibility validator** | Lists checks (side effects, annotations) without detection method. No pointer to data sources for side-effect inference. | Decisions may be opaque/unreliable, undermining auto-refactoring promises. | Define analyzers (dataflow, annotation catalog, heuristics) and document confidence levels per check. |

---

## 3. Performance & Scaling Concerns

1. **All-pairs comparison cost** – Even with early filtering, the design still compares every window pair. There is no sharding by hash buckets or module boundaries. Include concrete targets (e.g., <15 million comparisons) and describe scheduling strategy.
2. **Parallelization** – Document references “parallelization (8 cores)” but not the execution model (ForkJoin? Akka?). Need a plan for thread-safe access to shared structures and deterministic cluster formation.
3. **Memory footprint** – Records hold AST references, source text, and scope contexts. Provide memory budget per million tokens, along with strategies such as streaming tokenization, disk-backed stores, and reuse of per-file metadata.
4. **Incremental analysis** – No support for analyzing diffs or caching past runs, making CI usage expensive. Consider storing token hashes and signatures per file to reuse results when files are unchanged.

---

## 4. Competitive Landscape

| Tool | Capabilities | Gap vs. Design | Reality Check |
|------|--------------|----------------|---------------|
| **PMD CPD / Simian** | Token-based duplicate detection with suffix-tree indexing and language-agnostic support. | Design claims they are “text-based only,” ignoring CPD’s identifier-agnostic tokenization and incremental detection. | Clarify how semantic preservation surpasses CPD’s ignore-identifiers/Tokens options; consider extending CPD instead of rebuilding. |
| **SonarQube** | AST-based detection with block clustering, duplication metrics, and enterprise reporting. | Document states Sonar is “binary detection” yet Sonar already reports duplication percentages and tracks structural clones. | Analyze why Sonar’s AST fingerprints or duplication index cannot be leveraged; explain differentiators (e.g., refactoring metadata, test-aware strategies). |
| **CloneDR / NiCad / Deckard** | Mature academic/commercial tools using parse trees, characteristic vectors, or pretty-printed normalization to capture near-miss clones; some support suggested refactorings. | Not mentioned in design. | Include them in competitive analysis; note features lacking (automatic rewrites, test heuristics) while acknowledging their semantic clone detection strengths. |
| **IDE built-ins (IntelliJ, Eclipse)** | Offer duplicate detection, quick-fix refactoring suggestions within a file or project scope. | Design omits IDE-level support. | Recognize existing workflows to avoid overselling uniqueness; propose integration hooks instead. |

Without deeper differentiation, the “only tool that detects and refactors” claim is overstated. Highlight test-specific refactorings, variation tracking, and integration with Antikythera/TestFixer as the true advantages.

---

## 5. Missing Validation & Metrics

- **Calibration methodology** – Weighting (0.40/0.40/0.20) is marked heuristic but no plan exists for tuning or verifying precision/recall. Need datasets, labeling process, and acceptance thresholds.
- **Benchmark corpus** – Success criteria cite 10K sequences but no reference project set is listed. Identify representative services and anticipated LOC/token counts.
- **Quality gates** – Automated refactoring targets (>90% success) lack a test harness description. Define experiments (golden master diffs, mutation tests, CI gating) to prove safety.

---

## 6. Suggested Enhancements

1. **Candidate indexing** – Introduce hashed shingles, suffix arrays, or MinHash + LSH to form candidate buckets before expensive DP comparisons.
2. **Structural fingerprints** – Generate AST subtree hashes (Merkle hashing) or Deckard-style feature vectors for faster structural matching.
3. **Memory pooling** – Replace per-sequence AST references with lightweight handles and share per-file metadata (imports, compilation unit) across sequences. Track memory usage via metrics.
4. **Scope analyzer extension** – Document support for lambdas, inner classes, anonymous classes, and method references; define limitations when encountering unsupported constructs.
5. **Feasibility evidence** – Prototype side-effect heuristics and annotate confidence levels; plan for user confirmation when heuristic confidence < threshold.
6. **Competitive integration** – Investigate leveraging CPD or Sonar outputs as seed clusters, then layering refactoring metadata on top instead of reinventing detection fully.
7. **Evaluation roadmap** – Add a section on dataset selection, labeling workflow, and KPIs for tuning similarity weights and parameter caps.

---

## 7. Open Questions for Authors

1. How are window sizes chosen and de-duplicated to avoid redundant comparisons across overlapping regions?
2. Can LCS/Levenshtein matrices be reused or replaced with a single alignment algorithm to reduce compute cost?
3. What is the memory target per million tokens, and how will the design enforce it?
4. Which projects/datasets will validate the proposed similarity weights and refactoring success rates?
5. Are there plans to support incremental/delta analysis, or is every run full-scan only?
6. How will closures, lambdas, and anonymous classes be represented inside `ScopeContext`?
7. What is the fallback when Antikythera’s `AbstractCompiler` cannot provide all necessary AST hooks?

---

## 8. Conclusion

The design articulates a sophisticated end-to-end workflow, but the detection core needs stronger algorithmic rigor, memory discipline, and empirical validation. Addressing the issues above—and acknowledging overlapping capabilities offered by CPD, SonarQube, CloneDR, and NiCad—will make the solution more credible and achievable.

