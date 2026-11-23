> Exercise status: the missing completion notification has now been fixed.
> Both mode names are retained for comparing against historical run directories;
> baseline and incident now use the same corrected release behavior.
> Existing pre-fix results remain unchanged.

# Scheduler performance investigation

Java 21, no external dependencies, no GPU required. This isolated lab does not modify the application's scheduler. It models ready batches competing for two execution slots. Backend completion is a scheduled timer, not actual inference. Start with observations before opening IncidentLab.java, which contains the exercise implementation.

Run from this directory:

```bash
bash run.sh baseline
bash run.sh incident
```

Each run lasts about 12 seconds plus drain time and writes a new directory under runs/. Compare summary.txt first. Both modes receive the same open-loop load: eight ready batches every 250 milliseconds, with simulated service time of 40 ms. These are batches, not individual requests. The workload is deliberately bursty and the cause resembles the incident we discussed; this is practice collecting evidence, not a blind mystery.

## Your assignment

1. Record observations separately from explanations.
2. State two competing hypotheses and a measurement that distinguishes them.
3. Inspect events.csv only after choosing what evidence you need.
4. Predict how a proposed fix changes latency, throughput, and slot occupancy.
5. Edit the incident implementation yourself, keeping the baseline and workload constant.
6. Repeat each mode at least three times; report variation, correctness checks, and tradeoffs.

Do not optimize before recording your initial evidence. Send your summaries and hypothesis back for review; the assistant should not apply the fix for you.

## Measurement definitions and limitations

- ready_to_start: batch readiness to execution launch; not TTFT. Percentiles include only batches started by the measurement cutoff. Queued batches are censored, so always inspect backlog too.
- completion_rate: completions during the measurement window / actual elapsed time, excluding drain.
- ready_with_free_slot_ms: integrated wall time when ready work exists and fewer than two slots are owned. State is captured under one shared lock.
- slot_occupancy_percent: occupied slot-time / (two slots × elapsed time). This is NOT CPU or GPU utilization.
- events.csv: monotonic elapsed timestamps, batch identities, and consistent occupancy/queue observations. LOCK_WAIT_US events encode acquisition duration in their name.
- MEASUREMENT_END separates the load experiment from drain. Shutdown changes behavior to allow bounded cleanup; do not use drain events to diagnose steady-load behavior.
- drain_check verifies all accepted batches complete exactly once, capacity never exceeds two, and no slots remain owned. It is not an exhaustive concurrency proof.

Instrumentation allocates strings under the lock and perturbs timings. JVM startup/JIT, timer jitter and host load also matter. This is a diagnostic simulator, not a production performance benchmark. No actual GPU, first token, or CPU-utilization measurements are claimed.

## Additional evidence

Record Java Flight Recorder events:

```bash
JFR=1 bash run.sh incident 30
```

While running, the terminal prints a PID. In another terminal use your JDK's tool (replace PID with the printed number):

```bash
jcmd PID Thread.print > /tmp/scheduler-thread-dump.txt
```

Inspect the inference-scheduler thread. Repeat snapshots during the suspected gap; one snapshot can miss the problem. A thread state alone cannot distinguish every kind of wait; inspect its stack.

Inspect a recording with JDK Mission Control, or use the JDK command:

```bash
jfr print --events jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ExecutionSample runs/YOUR-RUN/recording.jfr
```

Optional controlled service-time experiment (duration seconds, service milliseconds):

```bash
bash run.sh baseline 12 80
bash run.sh incident 12 80
```

Predict the effect before running. Keep load constant. An 80 ms backend has an ideal ceiling of 25 batches/sec with two slots, below the offered 32, so backlog can grow even with correct scheduling.
