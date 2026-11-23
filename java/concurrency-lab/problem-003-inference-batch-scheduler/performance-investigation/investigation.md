# Investigation notebook

## Observations
Baseline run(s):
1. Completion rate is higher in baseline. 
2. 

Incident run(s):
1. Completion rate is lower in baseline. 
2. 

What changed? Include backlog and censored latency samples:

## Competing hypotheses
1. Scheduler is not being able to detect the available batches.
2. Scheduler is not being notified about the previous completion batches.
3. Completion batch couldn't release the completion slots?
4. Evidence that would falsify each:
- Check for detecting the completion batches, schduler metric?
- Schduler trigger frequency and matches completion?
- Execution slot avialbikity release time,a cquire time tracking?
## Next measurement
Question:
Measurement / event correlation:
Result:
Interpretation and remaining uncertainty:

## Proposed intervention
Mechanism:
Predicted metric changes:
Correctness risks:

## Validation
Same workload and environment?
Repeated runs and variation:
Capacity / exactly-once completion / cleanup checks:
Did the results support the prediction?
