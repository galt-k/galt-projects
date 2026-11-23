# Deadlock review worksheet

## Observations
Serial result:
Incident result:
Which invariant failed?

## Hypothesis before reading the thread dump
What could explain the stall?
What would disprove it?

## Evidence
For each worker: what does it own, what is it waiting for, and who can satisfy that wait?
Does evidence distinguish slow work from a dependency cycle?

## Proposed structural fix
What rule will every participating path obey?
Why does that rule prevent this failure rather than merely make it less likely?
What atomicity / ownership guarantees must remain intact?

## Validation
Serial and incident outcomes:
Candidate fix reasoning beyond this schedule:
Remaining limitations:
