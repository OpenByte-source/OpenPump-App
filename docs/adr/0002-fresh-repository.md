# 0002. Start from a fresh repository

Date: 2026-09-22 · Status: accepted

## Context
The private development history contains material that must never be published (private research notes, third-party books, personal identifiers) and is 459 MB.

## Decision
Publish a new repository whose first commit is the current tree, cleaned. The private repository stays as the archive.

## Consequences
Commit-level history before the first public commit is not public. Nothing unpublishable can leak through history; clones are small.
