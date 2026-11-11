---
name: calculate-hash
description: Calculate the hash of a given text.
---

# Calculate hash

This skill calculates the hash of a given text.

## Examples

* "Calculate hash of..."
* "What is the hash of..."

## Instructions

Call the `run_js` tool with the following exact parameters:

- script name: `index.html`
- data: A JSON string with the following field
  - text: the text to calculate hash for
  - algorithm: `SHA-1`, `SHA-256`, `SHA-384` or `SHA-512`, as the user asked; `SHA-1` if they named none

Say which algorithm the result names.
