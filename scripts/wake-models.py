#!/usr/bin/env python3
"""Rebuilds brain:wakeword's two models from openWakeWord v0.5.1 (ADR-121) and checks them.

wake_embedding.tflite is openWakeWord's embedding model as released. wake_melspectrogram.tflite is its mel model
with the input fixed at [1, 1760] and the output at [1, 1, 8, 32], because LiteRT's Kotlin CompiledModel can't
resize an input. The script checks that the fixed model, on LiteRT's CompiledModel, matches the original resized.

Needs Python 3.12 with ai-edge-litert, numpy and flatbuffers:
    uv venv -p 3.12 /tmp/wake && VIRTUAL_ENV=/tmp/wake uv pip install ai-edge-litert numpy flatbuffers
    /tmp/wake/bin/python -I scripts/wake-models.py brain/wakeword/src/main/assets
"""
import hashlib
import os
import sys
import tempfile
import urllib.request

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.compiled_model import CompiledModel
from ai_edge_litert.interpreter import Interpreter

RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/"
SAMPLES = 1760
EXPECTED = {
    "wake_embedding.tflite": "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8",
    "wake_melspectrogram.tflite": "48d429b781b7c18fb50f771578f32b11aa83fff5425ebafbff7da4b79f21d6aa",
}


def fetch(name, to):
    with urllib.request.urlopen(RELEASE + name) as response, open(to, "wb") as f:
        f.write(response.read())


def fix_mel(src, dst):
    buf = open(src, "rb").read()
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(buf, 0))
    graph = model.subgraphs[0]
    for index, shape in [(graph.inputs[0], [1, SAMPLES]), (graph.outputs[0], [1, 1, 8, 32])]:
        graph.tensors[index].shape = np.array(shape, dtype=np.int32)
        graph.tensors[index].shapeSignature = np.array(shape, dtype=np.int32)
    builder = flatbuffers.Builder(len(buf) + 1024)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    open(dst, "wb").write(builder.Output())


def check_mel(original, fixed):
    x = np.random.default_rng(0).normal(0, 3000, SAMPLES).astype(np.float32)[None, :]
    reference = Interpreter(model_path=original)
    reference.resize_tensor_input(0, [1, SAMPLES], strict=True)
    reference.allocate_tensors()
    reference.set_tensor(reference.get_input_details()[0]["index"], x)
    reference.invoke()
    want = reference.get_tensor(reference.get_output_details()[0]["index"]).reshape(-1)
    model = CompiledModel.from_file(fixed)
    inputs, outputs = model.create_input_buffers(0), model.create_output_buffers(0)
    inputs[0].write(x.reshape(-1))
    model.run_by_index(0, inputs, outputs)
    got = outputs[0].read(8 * 32, np.float32)
    difference = float(np.abs(want - got).max())
    if difference > 1e-4:
        sys.exit(f"The fixed mel model differs from the original by {difference}")


def main():
    out = sys.argv[1]
    with tempfile.TemporaryDirectory() as tmp:
        mel = os.path.join(tmp, "melspectrogram.tflite")
        fetch("melspectrogram.tflite", mel)
        fetch("embedding_model.tflite", os.path.join(out, "wake_embedding.tflite"))
        fix_mel(mel, os.path.join(out, "wake_melspectrogram.tflite"))
        check_mel(mel, os.path.join(out, "wake_melspectrogram.tflite"))
    for name, digest in EXPECTED.items():
        actual = hashlib.sha256(open(os.path.join(out, name), "rb").read()).hexdigest()
        print(f"{name} {actual} {'ok' if actual == digest else 'DIFFERENT'}")


main()
