# Java Concurrency Lab: Inference Serving Exercises

Java 21 learning implementations for request admission, token-budget ownership, and inference batch scheduling.

Start with the [LLM Inference Request Scheduler design](problem-003-inference-batch-scheduler/design.md) for the current architecture, invariants, verified behavior, and outstanding lifecycle work. This is an educational snapshot, not a production-ready inference service. Some tests remain disabled, and historical exercise notes may describe earlier stages.

Build: `mvn test` (JDK 21 and Maven required).

Modules:
- [Bounded admission queue](problem-001-bounded-inference-admission-queue/README.md)
- [Token budget](problem-002-inference-token-budget/README.md)
- [LLM Inference Request Scheduler](problem-003-inference-batch-scheduler/README.md)

Scheduler investigations use simulated execution; their measurements are not GPU inference benchmarks.
