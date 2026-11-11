/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Modified for Anu (ADR-131): an optional `algorithm` (SHA-1, SHA-256, SHA-384 or SHA-512; SHA-1 as in the
// original), named in the result, so an answer can't call a SHA-1 hash SHA-256.
const ALGORITHMS = ['SHA-1', 'SHA-256', 'SHA-384', 'SHA-512'];

async function digestMessage(message, asked) {
  const algorithm = String(asked || 'SHA-1').toUpperCase().replace(/^SHA(\d)/, 'SHA-$1');
  if (!ALGORITHMS.includes(algorithm)) {
    return {error: `Unsupported algorithm ${asked}. Use one of ${ALGORITHMS.join(', ')}.`};
  }
  const msgUint8 = new TextEncoder().encode(message); // encode as (utf-8) Uint8Array
  const hashBuffer = await crypto.subtle.digest(algorithm, msgUint8); // hash the message
  const hashArray = Array.from(new Uint8Array(hashBuffer)); // convert buffer to byte array
  const hashHex = hashArray
    .map((b) => b.toString(16).padStart(2, '0'))
    .join(''); // convert bytes to hex string
  return {algorithm, result: hashHex};
}

window['ai_edge_gallery_get_result'] = async (data) => {
  try {
    const jsonData = JSON.parse(data);
    return JSON.stringify(await digestMessage(jsonData['text'], jsonData['algorithm']));
  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `Failed to calculate hash: ${e.message}`});
  }
};
