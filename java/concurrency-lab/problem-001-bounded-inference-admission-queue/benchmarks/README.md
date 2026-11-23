# Optional benchmark worksheet

Add a JMH harness only after the correctness suite is enabled and passing.

Plan workloads with 16 producers and one scheduler, capacity 1024, varying cancellation rates, and both saturated and unsaturated admission. Keep accepted, rejected-full, rejected-shutdown, cancelled, and dispatched counts separate. Record throughput and admission/dispatch latency distributions, allocation rate, warmup, forks, JDK, CPU, and workload seeds. Keep setup and teardown out of timed work and drain or account for all accepted submissions.

Chosen workloads, measurement method, and results: TODO

No benchmark implementation or performance target is supplied.
