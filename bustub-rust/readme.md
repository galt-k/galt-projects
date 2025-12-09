# bustub-rust

A **Rust** implementation of the B+Tree Index from Carnegie Mellon University's 15-445/645 (Introduction to Database Systems) course – Project 2.

This repository contains an unofficial port of the [CMU 15-445/645 Fall 2024 Project #2: B+Tree Index](https://15445.courses.cs.cmu.edu/fall2024/project2/) from the original C++ BusTub codebase to Rust.

The goal is to re-implement the required B+Tree functionality using Rust's type system, ownership model, generics, and safe concurrency primitives while staying as close as possible to the original assignment specifications.

**Disclaimer:** This is an independent learning project and is not affiliated with Carnegie Mellon University or the official BusTub repository.

## Original Assignment Overview

The original project requires students to implement a disk-oriented, thread-safe B+Tree index that integrates with BusTub's buffer pool manager. Key requirements include:

- Unique keys only (duplicate keys are ignored on insert)
- Fixed-size page layouts using arrays (no dynamic containers inside pages)
- Insertion and deletion with proper splitting, coalescing, and redistribution
- Point lookups (`GetValue`) and range scans via an iterator
- Fine-grained concurrency using latch crabbing (no global tree latch)

### Tasks

1. Implement B+Tree page structures (`BPlusTreeHeaderPage`, `BPlusTreeInternalPage`, `BPlusTreeLeafPage`)
2. Core operations: insert, delete, point lookup with correct split/merge logic
3. Index iterator for ordered key scans using leaf page sibling pointers
4. Thread-safety via latch crabbing with page-level read/write locks

## Rust Implementation Goals

This port aims to:

- Use safe Rust abstractions (no `unsafe` code where possible)
- Leverage generics for key and value types (requiring `Ord` and other traits)
- Simulate page-level latches with `RwLock` or similar for concurrency
- Implement a proper `Iterator` for range scans
- Provide comprehensive tests equivalent to the original grading suite

## Project Structure
