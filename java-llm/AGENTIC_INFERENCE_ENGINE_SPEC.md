Markdown
# AGENTIC_INFERENCE_ENGINE_SPEC.md — First-Principles Staff-Level Java 21+ Implementation Guide (Chapter 5: Stateful Inference, Constrained Decoding & Distributed Control Plane — Stanford CS349D Edition)

## 1. Role & Strict Pedagogical Rules for Gemini Code Assist

You are my AI pair-programming Staff Systems Mentor. My goal is to understand and build a production-grade **Agentic LLM Inference Runtime & Distributed Control Plane** from first principles in **modern Java 21+** (80% pure zero-dependency JDK 21+ using flat arrays / `MemorySegment` / `Vector API`, paired with 20% Python `vLLM`/`SGLang` verification benchmarks), building directly on top of the `Tensor`, `MultiHeadAttention`, `LayerNorm`, `FeedForward`, `TransformerBlock`, and stateless `GPTModel` primitives we established in `GPT_ARCHITECTURE_SPEC.md` (Chapter 4).

### STRICT RULES (DO NOT VIOLATE):
1. **No Unsolicited Code:** NEVER write the implementation of a Java class or method for me unless I explicitly ask for the solution. Your job is to guide *me* to write it.
2. **Strict Concept Gating (Build Only on What Is Established):**
    - You may ONLY reference primitives we have already established in Chapters 3 & 4 (`tokens`, `rows`, `columns`, `features`, `weights`, `matrix multiplication`, `dot products`, `Query/Key/Value projections`, `causal mask`, `softmax`, `context vectors`, `heads`, `flat strided float[] Tensors`, `LayerNorm`, `GELU`, `FeedForward`, `Residual/Shortcut connections`, `Token/Position Embeddings`, `Vocabulary Logits [B, T, V]`, and `greedy argmax loop`) PLUS the specific primitive being introduced in the current Milestone and prior completed Milestones.
    - **FORBIDDEN:** Do NOT jump ahead or mention future concepts before their milestone arrives (e.g., during Milestone 2 `RadixPrefixCache`, do NOT mention `FSM Logit Masks`, `Shannon Entropy`, `Continuous Batching`, `Preemption`, `Speculative Decoding`, or `Disaggregated Routing`).
3. **Top-Down / Bottom-Up Methodology:** For every milestone, strictly enforce two phases:
    - **Step A: Top-Down (System Contract, Business SLA & First-Principles "Why"):** Before I write any loops or index arithmetic, ask me Socratic questions to define:
        1. *What specific architectural failure or business SLA breach occurs if this component is missing?* (using only established primitives).
        2. *What is the exact Java API/record signature, memory layout, and input/output tensor or state invariant?*
    - **Step B: Bottom-Up (Memory, Math & Scheduling Primitives):** Once the contract is locked, guide me to implement the exact flat-array index arithmetic, page-table pointer offsets, online rescaling loops, bitmask operations, token-budget schedulers, and IEEE 754 floating-point mechanics.
4. **What We Are Eliminating (Do NOT ask me to implement these):**
    - Skip JNI/CUDA C++ bindings—we are building a deterministic, hardware-accurate reference runtime in pure Java 21+ where flat arrays / `MemorySegment` slabs model GPU High Bandwidth Memory (HBM), on-chip SRAM registers, and Host DRAM.
    - Skip backward passes, gradients, or weight updates (`.backward()`, LoRA, or GRPO)—weights $\theta$ are strictly frozen ($\Delta\theta = 0$) across this entire specification.
    - Skip `java.util.regex` inside token decoding loops—all grammar state machines must operate on pre-compiled integer token ID bitmasks.

---

## 2. Established Foundation (From Chapters 3 & 4)
Before starting Milestone 1, we treat ONLY these primitives as known:
- **Flat Strided `Tensor`:** A 1D `float[] data` array backed by `int[] shape` and `int[] strides`, supporting `matmul`, row-wise `softmax`, and slice extraction.
- **Stateless `MultiHeadAttention`:** Takes input `X` of shape `[B, T, emb_dim]`, computes `Q = X @ W_q`, `K = X @ W_k`, `V = X @ W_v` (reshaped to `[B, n_heads, T, head_dim]`), computes scaled dot-product scores $\frac{Q K^T}{\sqrt{d_k}}$ with a triangular $-\infty$ causal mask, applies `softmax`, and multiplies by `V`.
- **Stateless `GPTModel.forward(int[][] idx)`:** Takes `[B, T]` token IDs, looks up `tok_emb[idx] + pos_emb[0..T-1]`, passes `[B, T, 768]` through 12 `TransformerBlock` layers and `final_norm`, and projects to `logits` of shape `[B, T, 50257]`.
- **Stateless `generateTextSimple`:** To generate $N$ tokens from a prompt of length $T_0$, it runs `GPTModel.forward` $N$ times, feeding `[B, T_0]`, then `[B, T_0 + 1]`, ..., `[B, T_0 + N - 1]`, extracting only the last row `logits[:, -1, :]` each time.

---

## 3. Part I: Intra-Node Execution Engine (Milestones 1–4)

### Milestone 1: Paged KV Cache, Grouped-Query Attention (GQA) & Single-Pass Online Softmax — `PagedKVCache.java` & `FlashPagedAttention.java` [COMPLETED]
*(Stanford CS349D Lectures 3, 10 & 11: PagedAttention, FlashAttention-2 / FlashInfer, & GQA)*
- **First-Principles Problem:** Redundant recomputation of historical token projections ($O(T^2)$ complexity per step), severe VRAM wastage caused by contiguous memory fragmentation, redundant KV head storage across query heads, and intermediate `[1, T]` score buffer allocations during two-pass softmax.
- **Business Implication:** Inflated GPU infrastructure costs, memory-bandwidth saturation, and severe concurrency limits that prevent scaling enterprise agent workloads.
- **Implemented & Verified Classes:**
    - `PagedKVCache.java`: Contiguous physical block pool with $O(1)$ strided address translation (`PagedKVCacheTest.java`).
    - `FlashPagedAttention.java`: Single-pass streaming online softmax kernel with GQA head sharing and zero heap allocations (`FlashPagedAttentionTest.java`).
    - `InferenceConfig.java`: Standardized hyperparameter configuration record.
    - `CachedAttention.java`: Stateful prefill and decode attention (`CachedAttentionTest.java`).
    - `CachedTransformerBlock.java` & `CachedGPTModel.java`: Multi-layer end-to-end stateful decode model (`CachedGPTModelTest.java`).
- **Strict Equivalence Invariant (Verified):** The context vector and final logits `[B, 1, V]` emitted at step $t$ using single-pass `FlashPagedAttention` match two-pass standard softmax and Chapter 4's stateless `GPTModel.forward(idx[0..t])` within IEEE 754 `float32` tolerance (`1e-5f`).

---

### Milestone 2: Zero-Copy Prefix Sharing Across Agent Turns — `RadixPrefixCache.java` [ACTIVE]
*(Stanford CS349D Lecture 3 & 8: SGLang RadixAttention)*
- **First-Principles Problem:** Only introduce after Milestone 1 is complete. Redundant prefill computation and duplicate physical block allocation for identical system prompts and conversation histories across multi-turn agent loops.
- **Business Implication:** Sluggish Time-To-First-Token (TTFT) and wasted GPU compute spending during long-running, multi-step agent investigations.
- **Top-Down Contract:**
    - Every full block of `BLOCK_SIZE` tokens is keyed by its exact token sequence `int[BLOCK_SIZE]` chained from its parent block node in a Radix Tree with reference counting (`refCount`).
    - `PrefixMatchResult matchPrefix(int[] tokenIds)` walks the Radix Tree, increments `refCount` on matched physical blocks, and returns `int[] sharedPhysicalBlocks` plus `int cachedTokenCount` (always a multiple of `BLOCK_SIZE`, leaving at least 1 delta token for `forwardDelta()`).
    - Unreferenced leaf blocks (`refCount == 0`) are evicted in **Least-Recently-Used (LRU)** timestamp order when the physical block pool is full.
- **Bottom-Up Math & Mechanics:**
    - **Rectangular Delta-Prefill Attention:** Given total length $T$ and `cachedTokenCount = C` ($0 < C < T$), compute $Q, K, V$ projections **only** for the $T - C$ delta tokens (`pos = C .. T-1`). Write the new $K, V$ rows into newly allocated blocks at `blockTable[C/B_s ..]`, and attend the $T - C$ queries against all $T$ keys (`0 .. T-1`) where query row $i \in [0, T-C-1]$ streams Online Softmax over keys $j \in [0, C + i]$.
- **Verification Target (Milestone 2 Ground Truth):**
    - Run Turn 1 (`[10, 20, 30, 40, 50, 60]`), committing block `[10, 20, 30, 40]` (`physicalBlockId = 0`) to `RadixPrefixCache`. Run Turn 2 (`[10, 20, 30, 40, 50, 60, 70, 80]`). Verify `matchPrefix` returns `cachedTokenCount = 4` (`refCount = 2`), computes projections only for `[50, 60, 70, 80]`, and produces final logits matching a full 8-token prefill within `1e-5f`.

---

### Milestone 3: Deterministic Grammar & Schema Enforcement — `FSMLogitMasker.java`
*(Outlines & XGrammar Pushdown/FSM Bitmask Compilation)*
- **First-Principles Problem:** Only introduce after Milestone 2 is complete. Syntax failures and JSON schema violations resulting from unconstrained stochastic token selection during structured agent tool calls.
- **Business Implication:** Downstream parser crashes, broken automated microservice integrations, and expensive error-handling retry loops.
- **Top-Down Contract:**
    - Compile a schema into a Deterministic Finite State Machine (FSM) with states $s \in \{0 \dots S-1\}$, transitions `int[][] stateTokenTransitions` (`[numStates, vocabSize]`), and packed 64-bit vocabulary masks `long[][] stateBitmasks` (`[numStates, ceil(V/64)]`).
    - `void applyMaskInPlace(float[] logits, int currentState)` overwrites `logits[v] = Float.NEGATIVE_INFINITY` whenever bit `(stateBitmasks[currentState][v >>> 6] & (1L << (v & 63))) == 0L`.
- **Bottom-Up Math & Mechanics:**
  $$\tilde{z}_v = \begin{cases} z_v & \text{if } \delta(s_t, v) \neq -1 \\ -\infty & \text{if } \delta(s_t, v) = -1 \end{cases}, \quad P(y_t = v \mid y_{<t}, s_t) = \frac{\exp(\tilde{z}_v / T)}{\sum_{j=0}^{V-1} \exp(\tilde{z}_j / T)}$$
  Because $\exp(-\infty) = 0.0$, illegal tokens receive strictly $0.0$ probability mass, redistributing 100% of probability across valid schema tokens in $O(V/64)$ bitwise operations.
- **Verification Target (Milestone 3 Ground Truth):**
    - Using Fixture C in Section 5, feed adversarial logits where `"INVALID"` (`token 5`) has score `+99.0f` at every step. Verify `FSMLogitMasker` deterministically emits `[0, 1, 2, 4, 7]` (`{"act":"ALLOW"}<EOS>`) with zero retries.

---

### Milestone 4: Uncertainty Quantification & Copy-on-Write Tree Search — `EntropyBranchScheduler.java`
*(Test-Time Compute Scaling & Entropix Uncertainty Sampling)*
- **First-Principles Problem:** Only introduce after Milestone 3 is complete. Irreversible execution errors and local optimization traps caused by single-path greedy decoding during high-uncertainty decision steps.
- **Business Implication:** Unreliable agent decision-making in complex operational environments, raising the risk of high-stakes automation failures.
- **Top-Down Contract:**
    - At each step, compute **Shannon Entropy** $\mathcal{H}(p)$ (in bits) and **Varentropy** $\mathcal{V}(p)$ over the masked probability distribution $p$.
    - Whenever $\mathcal{H}(p) > \tau$, fork the active sequence into $K$ parallel branches by cloning only the `int[] blockTable` (`refCount++` on full blocks, Copy-on-Write for any partially filled tail block), roll out the branches, and select the trajectory maximizing length-normalized cumulative log-likelihood:
      $$\text{Score}(Y) = \frac{1}{|Y|} \sum_{t=1}^{|Y|} \ln P(y_t \mid y_{<t})$$
- **Bottom-Up Math & Mechanics:**
  $$\mathcal{H}(p) = -\sum_{v: p_v > 10^{-12}} p_v \log_2(p_v), \quad \mathcal{V}(p) = \sum_{v: p_v > 10^{-12}} p_v \left(-\log_2 p_v - \mathcal{H}(p)\right)^2$$
- **Verification Target (Milestone 4 Ground Truth):**
    - Using Fixture D in Section 5, verify that at State 2 (`p_2 = 0.52, p_3 = 0.48`), $\mathcal{H}(p) = 0.9988455\text{ bits} > \tau = 0.80$, triggering a 2-way CoW fork that rejects the greedy `"ALLOW"` trap (cumulative score `-2.2633f`) in favor of `"BLOCK"` (cumulative score `-0.7853f`).

---

## 4. Part II: Staff-Level Multi-Tenant Control Plane & Cluster Architecture (Milestones 5–8)

### Milestone 5: Iteration-Level Continuous Batching, Chunked Prefill & Agentic DAG Priority — `ContinuousBatchScheduler.java`
*(Stanford CS349D Lectures 5 & 15: Orca, Sarathi-Serve Chunked Prefill, & Autellix / Parrot Program-Level Agent Scheduling)*
- **First-Principles Problem:** Only introduce after Milestone 4 is complete. Static batching locks execution until the longest sequence finishes; unchunked long prefills stall concurrent decoders ("prefill-decode head-of-line blocking"); and request-blind FIFO scheduling starves multi-turn agent workflows on their final steps ("DAG-level head-of-line blocking").
- **Business Implication:** Catastrophic p99 Inter-Token Latency (ITL) spikes and blown end-to-end multi-step agent SLAs under multi-tenant load.
- **Top-Down Contract:**
    - The scheduler operates at **every single forward-pass iteration** with a strict `maxTokensPerStep` budget $B_{\text{tokens}}$ and tracks parent-child agent workflow state via `AgentWorkflowDAG` (`workflowId`, `cumulativeServiceTimeMs`, `estimatedRemainingSteps`).
    - **Rule 1 — Decode-First Priority (ITL Protection):** Every active sequence in `runningBatch` in the **Decode phase** is allocated `1` token from $B_{\text{tokens}}$ first.
    - **Rule 2 — `Autellix` Program-Level Critical-Path Ordering (CS349D Lecture 15):** Waiting requests in `waitingQueue` are ordered by **Shortest-Remaining-Workflow-First (SRWF) / Least-Cumulative-Service-Attained** across the entire agent DAG rather than isolated request arrival time, ensuring an agent on Step 9 of 10 finishes immediately instead of queueing behind a newly spawned 20-step workflow.
    - **Rule 3 — `Sarathi-Serve` Chunked Prefill Allocation (CS349D Lecture 5):** Remaining token budget $B_{\text{rem}} = B_{\text{tokens}} - N_{\text{decoding}}$ processes a chunk of at most $B_{\text{rem}}$ prompt tokens from the highest-priority prefill sequence across $\lceil T_p / B_{\text{rem}} \rceil$ iterations using Milestone 2's rectangular `forwardDelta()`.
- **Bottom-Up Math & Mechanics:**
    1. **Step Budget & Chunk Accounting:**
       $$N_{\text{decode}} = \sum_{s \in \text{runningDecode}} 1, \quad B_{\text{rem}} = \max(0, B_{\text{tokens}} - N_{\text{decode}})$$
       $$\text{chunkSize}(s_{\text{prefill}}) = \min\left(B_{\text{rem}}, \; s_{\text{prefill}}.\text{promptLen} - s_{\text{prefill}}.\text{prefilledTokens}\right)$$
    2. **Autellix Workflow Priority Score (Lower = Higher Priority):**
       $$\text{PriorityCost}(w) = w.\text{estimatedRemainingTokens} + \gamma_{\text{age}} \cdot w.\text{cumulativeServiceTokens}$$
- **Verification Target (Milestone 5 Ground Truth):**
    - Using Fixture E in Section 5 (`maxTokensPerStep = 4`), verify both:
        1. **Chunked Prefill:** Active decoders `SeqA` and `SeqB` consume $1+1=2$ tokens/step while 6-token prompt `SeqC` is sliced into three 2-token chunks (`[2, 2, 2]`) across Steps 1–3 without stalling `SeqA` or `SeqB`.
        2. **Autellix DAG Priority:** When `Workflow_1` (at Turn 4/5, `remainingTokens = 10`, `cumulativeService = 40`) and `Workflow_2` (at Turn 1/5, `remainingTokens = 100`, `cumulativeService = 0`) compete in `waitingQueue` ($\gamma_{\text{age}} = 0.5f$), `Workflow_1` (`cost = 30.0f`) is scheduled ahead of `Workflow_2` (`cost = 100.0f`).

---

### Milestone 6: KV Block Exhaustion, Multi-Tenant QoS Quotas & INT8 Quantized Host-Swap — `KVPreemptionManager.java` & `QuantizedKVCodec.java`
*(Stanford CS349D Lectures 9 & 18: CacheGen / Mooncake KV Quantization & LithOS / Prism Multi-Tenant QoS Isolation)*
- **First-Principles Problem:** Only introduce after Milestone 5 is complete. Simultaneous context expansion exhausts physical GPU blocks mid-generation (deadlock), noisy-neighbor tenants monopolize the shared KV pool, and swapping raw 32-bit `float[]` KV blocks across PCIe saturates host interconnect bandwidth.
- **Business Implication:** Mid-flight transaction OOM crashes, SLA starvation for high-priority enterprise tenants, and severe PCIe swap stalls during traffic bursts.
- **Top-Down Contract:**
    - **Part A: Multi-Tenant QoS Victim Selection (`LithOS` / `Prism` — CS349D Lecture 18):** Each tenant $u$ has a guaranteed block quota $Q_u$. When `freePhysicalBlocks < blocksNeeded` (after evicting `refCount == 0` Radix leaves), select the victim sequence $s_{\text{victim}}$ primarily from the tenant with the highest **Quota Over-Utilization Ratio** $\frac{\text{usedBlocks}(u)}{Q_u} > 1.0$ (breaking ties via LIFO arrival timestamp).
    - **Part B: Per-Block Symmetric `INT8` KV Quantization (`CacheGen` — CS349D Lecture 9):** When swapping $s_{\text{victim}}$'s physical `float[]` KV blocks to `hostCpuSwapPool` (or transferring over the network in Milestone 8), compress each block $B$ from 32-bit `float` to 8-bit signed `byte` (`-127 .. +127`) paired with a single `float scale` per block, slashing swap memory footprint and PCIe transfer bytes by **$4\times$**!
    - **Anti-Thrashing Watermark:** Swapped sequences resume only when `freePhysicalBlocks >= victim.requiredBlocks + watermarkBlocks`.\
- **Bottom-Up Math & Mechanics:**
    1. **Symmetric Group-Wise `INT8` Quantization & Dequantization:** For a physical block slice $x \in \mathbb{R}^{N}$ (where $N = \text{BLOCK\_SIZE} \times d_h$):\
       $$S = \frac{\max_{i} |x_i|}{127.0\text{f}}, \quad q_i = \text{ clamp}\left(\text{Math.round}\left(\frac{x_i}{S}\right), -127, 127\right) \in \text{byte}$$\
       $$\hat{x}_i = q_i \times S$$\
       *(Guard: if $\max_i |x_i| == 0.0\text{f}$, set $S = 1.0\text{f}$ and $q_i = 0$).*\
- **Verification Target (Milestone 6 Ground Truth):**
    - Using Fixture F in Section 5:
        1. **QoS Preemption:** Tenant A (`quota = 3`, `used = 2`, ratio `0.67`) runs `Seq1` (`arrival = 10`); Tenant B (`quota = 1`, `used = 2`, ratio `2.00` — over quota!) runs `Seq2` (`arrival = 5`). Even though `Seq1` arrived later (`t=10 > t=5`), verify `KVPreemptionManager` preempts `Seq2` because Tenant B exceeds its QoS quota (`2.00 > 1.0`).
        2. **INT8 KV Codec:** Quantize the 4-float block `[-1.27f, 0.635f, 0.0f, 1.016f]` into `byte[]` (`[-127, 64, 0, 102]`, `scale = 0.01f`) and verify dequantized reconstruction error is `< 0.005f` while byte size drops from `16 bytes` to `4 bytes` (+ `4-byte` scale header).

---

### Milestone 7: Memory-Wall Breaking via Speculative Decoding — `SpeculativeDecoder.java`
*(Stanford CS349D Lecture 7: Speculative Decoding & SpecInfer)*
- **First-Principles Problem:** Only introduce after Milestone 6 is complete. Autoregressive decoding reads the entire multi-gigabyte model weight matrix $\theta_{\text{target}}$ from HBM just to generate a single token per step (Matrix-Vector multiply), leaving 90%+ of the processor's arithmetic units idle.
- **Business Implication:** Severely capped per-agent token generation speed (TPOT) that makes multi-step reasoning traces too slow for synchronous payment or user-facing SLAs.
- **Top-Down Contract:**
    - Pair `GPTModel targetModel` ($P(\cdot)$) with lightweight `GPTModel draftModel` ($Q(\cdot)$) and lookahead $\gamma$.
    - **Step 1 (Drafting):** Run `draftModel` autoregressively $\gamma$ times to propose candidate tokens $\tilde{y}_1, \dots, \tilde{y}_\gamma$ and draft probabilities $q_1(\cdot), \dots, q_\gamma(\cdot)$.
    - **Step 2 (Parallel Target Verification):** Feed `[y_0, \tilde{y}_1, ..., \tilde{y}_\gamma]` (length $\gamma + 1$) into `targetModel.forwardDelta()` in a **single parallel forward pass** (Matrix-Matrix multiply), obtaining target distributions $p_1(\cdot), \dots, p_{\gamma+1}(\cdot)$ in a single weight read.
    - **Step 3 (Lossless Rejection Sampling & $O(1)$ KV Rollback):** For $i = 1 \dots \gamma$, accept $\tilde{y}_i$ if $u_i < \min\left(1.0, \frac{p_i(\tilde{y}_i)}{q_i(\tilde{y}_i)}\right)$. At the first rejected step $i$, truncate both Paged KV caches to $t + i - 1$ and sample replacement token $y'_i$ from the residual distribution $p^{\text{res}}_i(v) = \frac{\max(0, p_i(v) - q_i(v))}{\sum_j \max(0, p_i(j) - q_i(j))}$. If all $\gamma$ tokens are accepted, sample bonus token $\gamma + 1$ from $p_{\gamma+1}(\cdot)$.
- **Verification Target (Milestone 7 Ground Truth):**
    - Using Fixture G in Section 5 ($\gamma = 3$, `DRAFT_TOKENS = [1, 2, 0]`, `U_DRAWS = [0.40f, 0.85f, 0.10f]`), verify token 1 (`id=1`) is accepted ($0.875 \ge 0.40$), token 2 (`id=2`) is rejected ($0.333 < 0.85$), residual distribution equals `[0.75f, 0.25f, 0.0f, 0.0f]`, token 3 is discarded, and Paged KV Cache rolls back cleanly by 2 slots.

---

### Milestone 8: Disaggregated Prefill/Decode & Prefix-Aware Cluster Router — `DisaggregatedClusterRouter.java`
*(Stanford CS349D Lectures 6 & 14: DistServe / NVIDIA Dynamo Disaggregation & Preble Prefix-Affinity Routing)*
- **First-Principles Problem:** Only introduce after Milestone 7 is complete. Standard round-robin load balancers route Turn 2 of an agent's workflow to a different GPU node than Turn 1 (destroying node-local `RadixPrefixCache` hit rates), while colocating compute-heavy Prefill and bandwidth-heavy Decode on the same node causes resource contention across the fleet.
- **Business Implication:** Cluster-wide cache hit ratios collapse from 90% to near-zero in multi-node deployments, multiplying GPU fleet costs and violating tail-latency SLAs at scale.
- **Top-Down Contract:**
    - **Part A: `Preble` Prefix-Tree Affinity Routing (CS349D Lecture 14):** Score candidate node $n$ by combining its longest Radix prefix match $M_n$ against its active queue load $L_n$:
      $$\text{RouteScore}(n) = \frac{M_n}{\text{promptTokens.length}} - \\lambda \cdot \frac{L_n}{L_{\\max}}$$
    - **Part B: `DistServe` Disaggregated Prefill-to-Decode INT8 KV Transfer (CS349D Lectures 6 & 9):** `prefillNode` executes Prefill (`0 .. T_prompt - 1`), compresses the physical KV blocks using Milestone 6's `QuantizedKVCodec` (`INT8`), transmits the compact `KVTransferPayload` to `decodeNode` (which dequantizes into its local `PagedKVCache` and populates its `blockTable` without running a single prefill FLOP), and `decodeNode` immediately streams Decode tokens.
- **Verification Target (Milestone 8 Ground Truth):**
    - Using Fixture H in Section 5, verify `selectOptimalNode` selects `Node 2` (`0.65f > 0.55f`), and verify that transferring quantized KV blocks from `prefillNode` to `decodeNode` reduces wire payload bytes by $4\\times$ while preserving greedy next-token identity on `decodeNode`.

---

## 5. Ground-Truth Test Fixtures & Verification Data

Copy these exact deterministic constants into `InferenceTestFixtures.java` to verify all 8 milestones.

### A. Engine Configuration Record (`InferenceConfig`)
```java
// Deterministic Micro-GPT Configuration with GQA (2 Query Heads sharing 1 KV Head)
int VOCAB_SIZE = 8;
int CONTEXT_LENGTH = 16;
int EMB_DIM = 4;
int N_Q_HEADS = 2;
int N_KV_HEADS = 1; // GQA Group Ratio G = N_Q_HEADS / N_KV_HEADS = 2
int HEAD_DIM = 2;   // EMB_DIM / N_Q_HEADS
int N_LAYERS = 1;
int BLOCK_SIZE = 4; // 4 tokens per physical KV block
int MAX_PHYSICAL_BLOCKS = 16;
float EPS = 1e-5f;
```

### B. Milestones 1 & 2 Ground Truth: GQA Paged KV & Single-Pass Online Softmax (FlashInfer)
```java
// Shared KV Head 0 (N_KV_HEADS = 1, HEAD_DIM = 2) for a 6-token sequence (pos 0..5)
// Physical Block 0 holds pos 0..3; Physical Block 1 holds pos 4..5
float[][] K_SEQ_6X2 = {
    { 1.0f,  0.0f}, // pos 0 (Block 0, Offset 0)
    { 0.0f,  1.0f}, // pos 1 (Block 0, Offset 1)
    { 1.0f,  1.0f}, // pos 2 (Block 0, Offset 2)
    {-1.0f,  0.5f}, // pos 3 (Block 0, Offset 3) -- Block 0 Full & Committed to Radix Tree!
    { 0.5f, -0.5f}, // pos 4 (Block 1, Offset 0)
    { 1.5f,  0.5f}  // pos 5 (Block 1, Offset 1)
};

float[][] V_SEQ_6X2 = {
    { 1.0f,  2.0f}, // pos 0
    { 3.0f,  4.0f}, // pos 1
    { 5.0f,  6.0f}, // pos 2
    { 7.0f,  8.0f}, // pos 3
    { 9.0f, 10.0f}, // pos 4
    {11.0f, 12.0f}  // pos 5
};

// Decode Query Head 0 at pos = 5: q_5 = [1.0f, 0.5f], scale = 1 / sqrt(2.0) = 0.70710678f
float[] Q_POS_5 = { 1.0f, 0.5f };

// Expected scaled dot-product scores s_j = (q_5 . K[j]) / sqrt(2):
float[] EXPECTED_SCORES_POS_5 = {
    0.70710678f, 0.35355339f, 1.06066017f, -0.53033009f, 0.17677670f, 1.23743687f
};

// Expected Single-Pass Online Softmax Running Max (m_j) and Running Denominator (l_j) at each step j=0..5:
float[] EXPECTED_ONLINE_MAX_M = {
    0.70710678f, // j=0: m_0 = s_0
    0.70710678f, // j=1: max(0.70710678, 0.35355339)
    1.06066017f, // j=2: max(0.70710678, 1.06066017) -> triggers rescale exp(0.7071 - 1.0607)!
    1.06066017f, // j=3: max(1.06066017, -0.53033009)
    1.06066017f, // j=4: max(1.06066017, 0.17677670)
    1.23743687f  // j=5: max(1.06066017, 1.23743687) -> triggers final rescale!
};

float[] EXPECTED_ONLINE_SUM_L = {
    1.0000000f,  // j=0: 1.0
    1.7025661f,  // j=1: 1.0 + exp(0.35355339 - 0.70710678)
    2.1960402f,  // j=2: 1.7025661 * exp(0.70710678 - 1.06066017) + 1.0
    2.3997703f,  // j=3: 2.1960402 + exp(-0.53033009 - 1.06066017)
    2.8132091f,  // j=4: 2.3997703 + exp(0.17677670 - 1.06066017)
    3.3534157f   // j=5: 2.8132091 * exp(1.06066017 - 1.23743687) + 1.0
};

// Final Context Vector o = o_tilde / l_5 (identical to two-pass softmax!):
float[] EXPECTED_CONTEXT_POS_5 = {
    6.3544917f, 7.3544917f
};
```

### C. Milestone 3 Ground Truth: FSM Schema Transition Table ({"act":"ALLOW"|"BLOCK"}<EOS>)
```java
// Mini-Vocabulary:
// 0: "{" | 1: "\"act\":" | 2: "\"ALLOW\"" | 3: "\"BLOCK\"" | 4: "}" | 5: "INVALID" | 6: "???" | 7: "<EOS>"
int[][] FSM_TRANSITIONS = {
    // tok0, tok1, tok2, tok3, tok4, tok5, tok6, tok7
    {    1,   -1,   -1,   -1,   -1,   -1,   -1,   -1 }, // State 0: only tok 0 valid
    {   -1,    2,   -1,   -1,   -1,   -1,   -1,   -1 }, // State 1: only tok 1 valid
    {   -1,   -1,    3,    3,   -1,   -1,   -1,   -1 }, // State 2: tok 2 or tok 3 valid
    {   -1,   -1,   -1,   -1,    4,   -1,   -1,   -1 }, // State 3: only tok 4 valid
    {   -1,   -1,   -1,   -1,   -1,   -1,   -1,    5 }  // State 4: only tok 7 valid (5 = Accepting)
};
```

### D. Milestone 4 Ground Truth: Shannon Entropy & Decision Trap Fixture
```java
float[] PROBS_STATE_2 = { 0.0f, 0.0f, 0.52f, 0.48f, 0.0f, 0.0f, 0.0f, 0.0f };
float EXPECTED_ENTROPY_BITS_STATE_2 = 0.9988455f;

float[] PROBS_STATE_0 = { 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
float EXPECTED_ENTROPY_BITS_STATE_0 = 0.0f;
```

### E. Milestone 5 Ground Truth: Chunked Prefill & Autellix Agent-DAG Priority
```java
int MAX_TOKENS_PER_STEP = 4;
// Part 1: Chunked Prefill Schedule for [SeqA (decode), SeqB (decode), SeqC (6-token prefill)]
int[][] EXPECTED_STEP_TOKEN_BUDGETS = {
    { 1, 1, 2 }, // Step 1: SeqA=1, SeqB=1, SeqC prefills [10, 20]
    { 1, 1, 2 }, // Step 2: SeqA=1, SeqB=1, SeqC prefills [30, 40]
    { 1, 1, 2 }, // Step 3: SeqA=1, SeqB=1, SeqC prefills [50, 60] (prefill complete!)
    { 1, 1, 1 }  // Step 4: SeqA, SeqB, SeqC all decode 1 token in parallel
};

// Part 2: Autellix DAG Priority (gamma_age = 0.5f)
// Workflow 1 (Turn 4/5): remainingTokens = 10, cumulativeService = 40 -> cost = 10 + 0.5*40 = 30.0f
// Workflow 2 (Turn 1/5): remainingTokens = 100, cumulativeService = 0 -> cost = 100 + 0.5*0 = 100.0f
float EXPECTED_WORKFLOW_1_COST = 30.0f;
float EXPECTED_WORKFLOW_2_COST = 100.0f;
int EXPECTED_NEXT_SCHEDULED_WORKFLOW_ID = 1;
```

### F. Milestone 6 Ground Truth: Multi-Tenant QoS Preemption & CacheGen INT8 Block Quantization
```java
// Part 1: LithOS/Prism Multi-Tenant QoS Victim Selection
// Tenant A: quota = 3 blocks, used = 2 blocks (ratio = 0.667f), runs Seq1 (arrival = 10)
// Tenant B: quota = 1 block,  used = 2 blocks (ratio = 2.000f -> OVER QUOTA!), runs Seq2 (arrival = 5)
int EXPECTED_QOS_VICTIM_SEQ_ID = 2; // Preempts Seq2 despite older arrival because Tenant B is over quota!

// Part 2: CacheGen Symmetric INT8 Block Quantization
float[] RAW_KV_BLOCK_4 = { -1.27f, 0.635f, 0.0f, 1.016f };
float EXPECTED_INT8_SCALE = 0.01f; // maxAbs = 1.27f / 127.0f = 0.01f
byte[] EXPECTED_QUANTIZED_INT8_BYTES = { -127, 64, 0, 102 };
float[] EXPECTED_DEQUANTIZED_KV_BLOCK_4 = { -1.27f, 0.64f, 0.0f, 1.02f }; // Max abs error = 0.005f
```

### G. Milestone 7 Ground Truth: Speculative Decoding Acceptance & Residual Distribution
```java
int GAMMA = 3;
int[] DRAFT_TOKENS = { 1, 2, 0 };
float[] U_DRAWS = { 0.40f, 0.85f, 0.10f };

float[][] Q_DRAFT_PROBS_3X4 = {
    { 0.10f, 0.80f, 0.05f, 0.05f },
    { 0.10f, 0.20f, 0.60f, 0.10f },
    { 0.70f, 0.10f, 0.10f, 0.10f }
};

float[][] P_TARGET_PROBS_4X4 = {
    { 0.15f, 0.70f, 0.10f, 0.05f }, // Step 1: 0.70/0.80 = 0.875 >= 0.40 -> ACCEPT tok 1
    { 0.40f, 0.30f, 0.20f, 0.10f }, // Step 2: 0.20/0.60 = 0.333 <  0.85 -> REJECT tok 2!
    { 0.25f, 0.25f, 0.25f, 0.25f },
    { 0.10f, 0.10f, 0.10f, 0.70f }
};

float[] EXPECTED_RESIDUAL_PROBS_STEP_2 = { 0.75f, 0.25f, 0.0f, 0.0f };
```

### H. Milestone 8 Ground Truth: Preble Prefix-Affinity Routing & Disaggregated Transfer
```java
int PROMPT_LEN_M8 = 8;
float LOAD_PENALTY_LAMBDA = 0.5f;
float MAX_QUEUE_LOAD = 100.0f;

// Node 0 (matched=0, load=10), Node 1 (matched=8, load=90), Node 2 (matched=6, load=20)
float[] EXPECTED_NODE_ROUTE_SCORES = { -0.05f, 0.55f, 0.65f };
int EXPECTED_SELECTED_NODE_ID = 2;
```

---

### Ready to Begin Milestone 2 — Step A (Top-Down First-Principles Kickoff)

With Milestone 1 100% complete and verified, we are officially unlocked into **Milestone 2: Zero-Copy Prefix Sharing Across Agent Turns (`RadixPrefixCache.java`)**.
