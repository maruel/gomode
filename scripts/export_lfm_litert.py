#!/usr/bin/env -S uv run --frozen --script
# Export LFM2.5 models with INT4 weights, a 16K FP16 cache, and native LiteRT tools.
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = [
#   "huggingface-hub",
#   "jinja2",
#   "litert-torch @ git+https://github.com/google-ai-edge/litert-torch@71f57fa06a74401e4030e3a09ca06e60442cf0bf",
#   "torch==2.12.0",
# ]
# [tool.uv]
# prerelease = "allow"
# [tool.uv.sources]
# torch = { index = "pytorch-cpu" }
# [[tool.uv.index]]
# name = "pytorch-cpu"
# url = "https://download.pytorch.org/whl/cpu"
# explicit = true
# ///

import argparse
import contextlib
import dataclasses
import hashlib
import importlib.metadata
import json
import mmap
import pathlib
import shutil
import sys
from collections.abc import Iterator

import numpy as np
import torch
import transformers
from ai_edge_litert import schema_py_generated as tflite
from ai_edge_quantizer import qtyping, recipe
from ai_edge_quantizer.algorithms.uniform_quantize import uniform_quantize_tensor
from google.protobuf import text_format
from huggingface_hub import snapshot_download
from jinja2 import Environment, StrictUndefined
from litert_lm_builder import litertlm_header_schema_py_generated as schema
from litert_lm_builder import litertlm_peek
from litert_lm_builder.runtime.proto import llm_metadata_pb2
from litert_torch.generative.export_hf import export
from litert_torch.generative.export_hf.core import attention
from litert_torch.generative.export_hf.model_ext.lfm2 import short_conv


@dataclasses.dataclass(frozen=True)
class LfmVariantConfig:
    variant: str
    model_id: str
    model_revision: str
    output_name: str
    default_output_dir: pathlib.Path
    attention_layers: int
    conv_layers: int
    expected_kv: int
    expected_conv: int


VARIANTS: dict[str, LfmVariantConfig] = {
    "1.2b": LfmVariantConfig(
        variant="1.2b",
        model_id="LiquidAI/LFM2.5-1.2B-Instruct",
        model_revision="0f604ada3f766f9f257460c4c9f0b5d6f69d431b",
        output_name="LFM2.5-1.2B-Instruct_int4_16k_fp16.litertlm",
        default_output_dir=pathlib.Path("models/lfm2.5-1.2b-16k-fp16"),
        attention_layers=6,
        conv_layers=10,
        expected_kv=12,
        expected_conv=10,
    ),
    "2.6b": LfmVariantConfig(
        variant="2.6b",
        model_id="LiquidAI/LFM2.5-2.6B",
        model_revision="654f9463ce32b05d0429d76fe1f580b27d4c1ac0",
        output_name="LFM2.5-2.6B_int4_16k_fp16.litertlm",
        default_output_dir=pathlib.Path("models/lfm2.5-2.6b-16k-fp16"),
        attention_layers=8,
        conv_layers=22,
        expected_kv=16,
        expected_conv=22,
    ),
}

EXPORTER_REVISION = "71f57fa06a74401e4030e3a09ca06e60442cf0bf"
CONTEXT_LENGTH = 16_384
TEMPLATE_PATH = pathlib.Path(__file__).with_name("lfm2_chat_template.jinja")
SHORT_CONV_FORWARD = short_conv.Lfm2ShortConv.forward
QUANTIZATION_SCALES = uniform_quantize_tensor.tensor_zp_scale_from_min_max


def write_json(path: pathlib.Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def validate_template(template: str) -> None:
    """Reject templates that silently discard native calls or tool results."""
    messages = [
        {"role": "user", "content": [{"type": "text", "text": "Check candidate 12345."}]},
        {
            "role": "assistant",
            "content": [],
            "tool_calls": [{"name": "get_status", "arguments": {"id": "12345"}}],
        },
        {
            "role": "tool",
            "content": [{"type": "tool_response", "response": {"status": "Interview Scheduled"}}],
        },
    ]
    rendered = (
        Environment(undefined=StrictUndefined)
        .from_string(template)
        .render(messages=messages, tools=[], bos_token="<|startoftext|>", add_generation_prompt=True)
    )
    if 'get_status(id="12345")' not in rendered or "Interview Scheduled" not in rendered:
        raise ValueError("Chat template drops tool calls or results")
    if not rendered.endswith("<|im_start|>assistant\n<think>"):
        raise ValueError("Chat template must prefill the mandatory thinking prefix")


def prepare(output_dir: pathlib.Path, variant: LfmVariantConfig) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    snapshot = pathlib.Path(
        snapshot_download(
            variant.model_id,
            revision=variant.model_revision,
            allow_patterns=["*.json", "*.jinja", "LICENSE"],
        )
    )
    config = json.loads((snapshot / "config.json").read_text(encoding="utf-8"))
    if config["model_type"] != "lfm2" or config["max_position_embeddings"] < CONTEXT_LENGTH:
        raise ValueError("Checkpoint does not support LFM2 at 16K context")
    if (
        config["layer_types"].count("full_attention") != variant.attention_layers
        or config["layer_types"].count("conv") != variant.conv_layers
    ):
        raise ValueError(f"Unexpected {variant.variant} layer layout")

    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    validate_template(template)
    template_path = output_dir / "chat_template.jinja"
    template_path.write_text(template, encoding="utf-8")

    # Min/max is intentional; this is not the community OCTAV recipe.
    # Quantize only linears and embeddings; keep short convolutions in float.
    quantization = recipe.dynamic_wi4b32_afp32(
        operation_name=recipe.TFLOperationName.FULLY_CONNECTED,
    ) + recipe.dynamic_wi8c_afp32(operation_name=recipe.TFLOperationName.EMBEDDING_LOOKUP)
    recipe_path = output_dir / "quantization.json"
    write_json(recipe_path, quantization)

    metadata = llm_metadata_pb2.LlmMetadata()
    metadata.start_token.token_str = "<|startoftext|>"
    metadata.stop_tokens.add().token_str = "<|im_end|>"
    metadata.stop_tokens.add().token_str = "<|endoftext|>"
    metadata.pad_token.token_str = "<|pad|>"
    metadata.max_num_tokens = CONTEXT_LENGTH
    metadata.llm_model_type.lfm2.SetInParent()
    metadata.jinja_prompt_template = template
    metadata.supports_function_calling = True
    metadata.supports_thinking = True
    metadata.sampler_params.k = 50
    metadata.sampler_params.p = 1.0
    metadata.sampler_params.temperature = 0.1
    metadata.sampler_params.type = metadata.sampler_params.TOP_P
    channel = metadata.channels.add()
    channel.channel_name = "thought"
    channel.start = "<think>"
    channel.end = "</think>"
    metadata_path = output_dir / "llm_metadata.pbtext"
    metadata_path.write_text(text_format.MessageToString(metadata), encoding="utf-8")
    shutil.copyfile(snapshot / "LICENSE", output_dir / "LICENSE")
    return snapshot, recipe_path, metadata_path


def fp16_cache_attention(
    module: torch.nn.Module,
    query: torch.Tensor,
    key: torch.Tensor,
    value: torch.Tensor,
    attention_mask: torch.Tensor | None,
    **kwargs: object,
) -> tuple[torch.Tensor, torch.Tensor | None]:
    # The pinned LFM exporter stores FP16 caches but leaves queries FP32.
    # Keep storage FP16 and align reads with the query's compute dtype. The
    # bundle's fp32_fp16 preference lets the GPU delegate lower float compute.
    return attention.transposed_attention(
        module, query, key.to(query.dtype), value.to(query.dtype), attention_mask, **kwargs
    )


def fp16_conv_forward(
    module: short_conv.Lfm2ShortConv,
    hidden_states: torch.Tensor,
    past_key_values: transformers.cache_utils.Cache,
    cache_position: torch.Tensor | None = None,
    attention_mask: torch.Tensor | None = None,
    valid_mask: torch.Tensor | None = None,
) -> torch.Tensor:
    # Explicit casts avoid mixed-dtype StableHLO concatenate operands. Save
    # recurrent state in its original FP16 storage dtype after float compute.
    layer = past_key_values.layers[module.layer_idx]
    storage_dtype = layer.conv_states.dtype
    layer.conv_states = layer.conv_states.to(hidden_states.dtype)
    result = SHORT_CONV_FORWARD(module, hidden_states, past_key_values, cache_position, attention_mask, valid_mask)
    layer.conv_states = layer.conv_states.to(storage_dtype)
    return result


def nonzero_quantization_scales(
    min_value: np.ndarray,
    max_value: np.ndarray,
    num_bits: int,
    symmetric: bool,
    granularity: qtyping.QuantGranularity,
    clipping_values: np.ndarray | None = None,
    signed: bool = True,
) -> tuple[np.ndarray, np.ndarray]:
    zero_point, scale = QUANTIZATION_SCALES(
        min_value, max_value, num_bits, symmetric, granularity, clipping_values, signed
    )
    zero = scale == 0
    if np.any(zero):
        if not (symmetric and signed and uniform_quantize_tensor.is_blockwise(granularity)):
            raise ValueError("Zero scale outside signed symmetric blockwise quantization")
        # The upstream BF16 -> FP16 rounding underflows for zero and tiny
        # nonzero blocks. Use the smallest FP16 subnormal, not the much larger
        # normal floor. This survives serialization and works on CPU/XNNPACK.
        # Repair before quantizing weights so their codes use this same scale.
        floor = np.float32(np.nextafter(np.float16(0), np.float16(1)))
        scale = np.where(zero, floor, scale)
    return zero_point, scale


@contextlib.contextmanager
def lfm_fp16_export() -> Iterator[None]:
    transformers.AttentionInterface.register("lrt_transposed_attention", fp16_cache_attention)
    short_conv.Lfm2ShortConv.forward = fp16_conv_forward
    uniform_quantize_tensor.tensor_zp_scale_from_min_max = nonzero_quantization_scales
    try:
        yield
    finally:
        uniform_quantize_tensor.tensor_zp_scale_from_min_max = QUANTIZATION_SCALES
        short_conv.Lfm2ShortConv.forward = SHORT_CONV_FORWARD
        transformers.AttentionInterface.register("lrt_transposed_attention", attention.transposed_attention)


def validate_bundle(path: pathlib.Path, variant: LfmVariantConfig) -> None:
    """Check the packaged metadata and every exported cache signature before publication."""
    header = litertlm_peek.read_litertlm_header(str(path), sys.stdout)
    sections = header.SectionMetadata()
    kinds = [sections.Objects(index).DataType() for index in range(sections.ObjectsLength())]
    for required in (
        schema.AnySectionDataType.LlmMetadataProto,
        schema.AnySectionDataType.ExecutorMetadataProto,
        schema.AnySectionDataType.TFLiteModel,
    ):
        if kinds.count(required) != 1:
            raise ValueError("Expected one graph, one LLM metadata section, and one executor metadata section")
    with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for index in range(sections.ObjectsLength()):
            section = sections.Objects(index)
            if section.DataType() == schema.AnySectionDataType.LlmMetadataProto:
                if section.EndOffset() - section.BeginOffset() > 1024 * 1024:
                    raise ValueError("LLM metadata exceeds 1 MiB")
                metadata = llm_metadata_pb2.LlmMetadata.FromString(data[section.BeginOffset() : section.EndOffset()])
                if (
                    metadata.max_num_tokens != CONTEXT_LENGTH
                    or not metadata.llm_model_type.HasField("lfm2")
                    or not metadata.supports_function_calling
                    or not metadata.supports_thinking
                ):
                    raise ValueError("Bundle lost its 16K LFM tool/thinking metadata")
                if not any(
                    channel.channel_name == "thought" and channel.start == "<think>" and channel.end == "</think>"
                    for channel in metadata.channels
                ):
                    raise ValueError("Bundle lost its separate thought channel")
                validate_template(metadata.jinja_prompt_template)
            elif section.DataType() == schema.AnySectionDataType.TFLiteModel:
                model = tflite.Model.GetRootAs(data, section.BeginOffset())
                if model.SignatureDefsLength() == 0:
                    raise ValueError("Graph has no prefill/decode signatures")
                for signature_index in range(model.SignatureDefsLength()):
                    signature = model.SignatureDefs(signature_index)
                    graph = model.Subgraphs(signature.SubgraphIndex())
                    if not any(
                        graph.Tensors(tensor_index).Type() == tflite.TensorType.INT4
                        for tensor_index in range(graph.TensorsLength())
                    ):
                        raise ValueError("Graph contains no INT4 weights")
                    embedding_count = 0
                    for operator_index in range(graph.OperatorsLength()):
                        operator = graph.Operators(operator_index)
                        if (
                            model.OperatorCodes(operator.OpcodeIndex()).BuiltinCode()
                            == tflite.BuiltinOperator.EMBEDDING_LOOKUP
                        ):
                            embedding_count += 1
                            if graph.Tensors(operator.Inputs(1)).Type() != tflite.TensorType.INT8:
                                raise ValueError("Embedding lookup weights are not INT8")
                    if embedding_count == 0:
                        raise ValueError("Graph contains no INT8 embedding lookup")
                    kv_count = conv_count = 0
                    for input_index in range(signature.InputsLength()):
                        entry = signature.Inputs(input_index)
                        name = entry.Name().decode().removeprefix("kv_cache_")
                        if name.startswith(("k_", "v_", "c_")):
                            tensor = graph.Tensors(entry.TensorIndex())
                            if tensor.Type() != tflite.TensorType.FLOAT16:
                                raise ValueError(f"Cache {name} is not FP16")
                            if name.startswith(("k_", "v_")):
                                shape = [tensor.Shape(axis) for axis in range(tensor.ShapeLength())]
                                if CONTEXT_LENGTH not in shape:
                                    raise ValueError(f"Cache {name} does not hold 16K tokens: {shape}")
                                kv_count += 1
                            else:
                                conv_count += 1
                    for output_index in range(signature.OutputsLength()):
                        entry = signature.Outputs(output_index)
                        if entry.Name().decode().startswith("kv_cache_"):
                            if graph.Tensors(entry.TensorIndex()).Type() != tflite.TensorType.FLOAT16:
                                raise ValueError(f"Cache output {entry.Name().decode()} is not FP16")
                    if kv_count != variant.expected_kv or conv_count != variant.expected_conv:
                        raise ValueError(
                            f"Signature has {kv_count} KV inputs and {conv_count} conv inputs; "
                            f"expected {variant.expected_kv}/{variant.expected_conv}"
                        )


def main() -> int:
    parser = argparse.ArgumentParser(description="Export LFM2.5 models to 16K FP16 LiteRT-LM.")
    parser.add_argument(
        "--variant",
        choices=sorted(VARIANTS.keys()),
        default="1.2b",
        help="LFM variant to export (default: 1.2b)",
    )
    parser.add_argument(
        "--output-dir",
        type=pathlib.Path,
        default=None,
        help="Output directory (defaults to models/lfm2.5-<variant>-16k-fp16)",
    )
    parser.add_argument(
        "--prepare-only", action="store_true", help="Validate and write export inputs without downloading weights."
    )
    args = parser.parse_args()
    variant = VARIANTS[args.variant]
    output_dir = (args.output_dir or variant.default_output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / variant.output_name
    if target.exists():
        parser.error(f"Refusing to overwrite {target}")
    snapshot, recipe_path, metadata_path = prepare(output_dir, variant)
    options = {
        "cache_length": CONTEXT_LENGTH,
        "experimental_use_fp16": True,
        "keep_temporary_files": True,
        "litert_lm_llm_metadata_override": str(metadata_path),
        "prefill_lengths": [1, 32, 64, 128, 256, 512, 1024],
        "quantization_recipe": str(recipe_path),
    }
    write_json(output_dir / "export_options.json", options)
    versions = {
        distribution.metadata["Name"]: distribution.version for distribution in importlib.metadata.distributions()
    }
    write_json(output_dir / "environment.json", versions)
    print(f"Prepared 16K INT4/FP16 export inputs for {variant.variant} in {output_dir}", flush=True)
    if args.prepare_only:
        return 0

    # Download the original BF16 checkpoint, never an existing quantized bundle.
    snapshot_download(variant.model_id, revision=variant.model_revision, allow_patterns=["*.safetensors"])
    work_dir = output_dir / "work"
    with lfm_fp16_export():
        export.export(model=str(snapshot), output_dir=str(work_dir), **options)
    bundles = list(work_dir.glob("*.litertlm"))
    if len(bundles) != 1:
        raise RuntimeError(f"Expected one exported bundle in {work_dir}, found {len(bundles)}")
    bundle = bundles[0]
    validate_bundle(bundle, variant)
    with bundle.open("rb") as stream:
        checksum = hashlib.file_digest(stream, "sha256").hexdigest()
    write_json(
        output_dir / "manifest.json",
        {
            "model": variant.model_id,
            "model_revision": variant.model_revision,
            "exporter_revision": EXPORTER_REVISION,
            "file": target.name,
            "sha256": checksum,
            "size_bytes": bundle.stat().st_size,
            "context_length": CONTEXT_LENGTH,
            "weights": "INT4 blockwise-32 min/max linears; INT8 embeddings; float convolutions",
            "cache_dtype": "float16",
            "activation_preference": "fp32_fp16",
            "validation": "exported; native inference and Android tool-call validation pending",
        },
    )
    bundle.replace(target)
    print(f"Exported candidate: {target}\nSHA-256: {checksum}", flush=True)
    print("Native inference, long-context quality, and Android tool-call validation remain required.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
