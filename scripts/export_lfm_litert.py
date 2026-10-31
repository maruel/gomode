#!/usr/bin/env -S uv run --locked --script
# Export LFM3 with a 64K FP16 cache, source-faithful tools, and validated LiteRT metadata.
# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = [
#   "ai-edge-litert-nightly==2.4.0.dev20261022",
#   "ai-edge-quantizer-nightly==0.10.0.dev20261023",
#   "huggingface-hub",
#   "jinja2",
#   "litert-converter==0.6.0.dev20261022",
#   "litert-lm-builder==0.18.1",
#   "litert-torch @ git+https://github.com/google-ai-edge/litert-torch@f61d0b792013300ae7a1ca215d8c2f9de5ef4adf",
#   "tf-nightly==2.23.0.dev20261028",
#   "torch==2.14.0",
#   "transformers==5.20.2",
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
import hashlib
import importlib.metadata
import json
import mmap
import pathlib
import shutil
import sys
from collections.abc import Iterator
from unittest import mock

# Import the exporter before Transformers remote modeling modules: it installs
# dialects and inference-only normalization patches during import.
import litert_torch  # noqa: F401 -- registers export dialects before remote modeling code loads.
import torch
import transformers
from ai_edge_litert import schema_py_generated as tflite
from ai_edge_quantizer import recipe
from google.protobuf import text_format
from huggingface_hub import snapshot_download
from jinja2 import Environment
from lfm3_export import extension, loader, patch
from litert_lm_builder import litertlm_header_schema_py_generated as schema
from litert_lm_builder import litertlm_peek
from litert_lm_builder.runtime.proto import llm_metadata_pb2
from litert_torch.generative.export_hf import export
from litert_torch.generative.export_hf.core import attention, export_lib

MODEL_ID = "LiquidAI/LFM3-1.7B"
MODEL_REVISION = "40ebe0aa6662c9d40389c2f5d565b6a2c6aebacd"
EXPORTER_REVISION = "f61d0b792013300ae7a1ca215d8c2f9de5ef4adf"
EXTENSION_REVISION = "f3b7baad40ec0f6a4b413912a68497bf8c5d8aa3"
CONTEXT_LENGTH = 65_536
OUTPUT_NAME = "LFM3-1.7B_int8_64k_fp16.litertlm"
TEMPLATE_PATH = pathlib.Path(__file__).with_name("lfm3_chat_template.jinja")
SHORT_CONV_FORWARD = patch.Lfm3LiteRTShortConv.forward


def write_json(path: pathlib.Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256_file(path: pathlib.Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_template(template: str) -> None:
    """Exercise tool history, literal parameters, results and stable thought prefixes."""
    environment = Environment()
    environment.globals["raise_exception"] = lambda message: (_ for _ in ()).throw(ValueError(message))
    compiled = environment.from_string(template)
    messages = [
        {"role": "user", "content": "Check candidate 12345."},
        {
            "role": "assistant",
            "content": "",
            "channels": {"thought": "Keep current-turn reasoning."},
            "tool_calls": [{"name": "get_status", "arguments": {"id": "12345", "active": True}}],
        },
        {"role": "tool", "content": [{"type": "text", "text": "Interview Scheduled"}]},
    ]
    tools = [{"name": "get_status", "parameters": {"type": "object", "properties": {"id": {"type": "string"}}}}]

    def render(history: list[dict[str, object]], generation: bool) -> str:
        return compiled.render(
            messages=history, tools=tools, bos_token="<|startoftext|>", add_generation_prompt=generation
        )

    rendered = render(messages, True)
    for expected in (
        "<tools>",
        "<think>Keep current-turn reasoning.</think>",
        "<function=get_status>",
        "<parameter=id>\n12345\n</parameter>",
        "<parameter=active>\ntrue\n</parameter>",
        "<tool_response>\nInterview Scheduled\n</tool_response>",
    ):
        if expected not in rendered:
            raise ValueError(f"Chat template lost {expected!r}")
    if not rendered.endswith("<|im_start|>assistant\n<think>"):
        raise ValueError("Chat template lost its thinking prefill")
    completed = render(messages, False)
    later = render([*messages, {"role": "user", "content": "Continue."}], True)
    if not later.startswith(completed):
        raise ValueError("Later user turns changed the rendered prompt prefix")


def prepare(output_dir: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    snapshot = pathlib.Path(
        snapshot_download(
            MODEL_ID,
            revision=MODEL_REVISION,
            allow_patterns=["*.json", "*.jinja", "*.py", "LICENSE"],
        )
    )
    config = json.loads((snapshot / "config.json").read_text(encoding="utf-8"))
    if config["model_type"] != "lfm3" or config["max_position_embeddings"] < CONTEXT_LENGTH:
        raise ValueError("Checkpoint does not support LFM3 at 64K context")
    if [config["layer_types"].count(kind) for kind in ("full_attention", "sliding_attention", "conv")] != [6, 5, 13]:
        raise ValueError("Unexpected LFM3 attention/convolution layout")

    template = TEMPLATE_PATH.read_text(encoding="utf-8")
    validate_template(template)
    (output_dir / "chat_template.jinja").write_text(template, encoding="utf-8")

    # Use per-channel INT8 matrix weights throughout; recurrent/KV state stays FP16.
    recipe_path = output_dir / "quantization.json"
    write_json(recipe_path, recipe.dynamic_wi8c_afp32())

    metadata = llm_metadata_pb2.LlmMetadata()
    metadata.start_token.token_ids.ids.append(config["bos_token_id"])
    metadata.stop_tokens.add().token_ids.ids.append(config["eos_token_id"])
    metadata.pad_token.token_ids.ids.append(config["pad_token_id"])
    metadata.max_num_tokens = CONTEXT_LENGTH
    # LiteRT-LM has no LFM3 output parser. Its generic processor preserves the
    # model's trained XML-like format; Go Mode parses it at the runner boundary.
    metadata.llm_model_type.generic_model.SetInParent()
    metadata.jinja_prompt_template = template
    metadata.supports_function_calling = True
    metadata.supports_thinking = True
    metadata.min_runtime_version = "0.18.1"
    metadata.sampler_params.k = 50
    metadata.sampler_params.p = 1.0
    metadata.sampler_params.temperature = 0.1
    metadata.sampler_params.type = metadata.sampler_params.TOP_P
    channel = metadata.channels.add()
    channel.channel_name = "thought"
    channel.start = "<think>"
    channel.end = "</think>"
    channel.is_reasoning_channel = True
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
    # Storage stays FP16; matmul reads match FP32 queries during conversion.
    return attention.transposed_attention(
        module, query, key.to(query.dtype), value.to(query.dtype), attention_mask, **kwargs
    )


def fp16_conv_forward(
    module: patch.Lfm3LiteRTShortConv,
    hidden_states: torch.Tensor,
    past_key_values: transformers.cache_utils.Cache,
    cache_position: torch.Tensor | None = None,
    attention_mask: torch.Tensor | None = None,
    valid_mask: torch.Tensor | None = None,
) -> torch.Tensor:
    layer = past_key_values.layers[module.layer_idx]
    storage_dtype = layer.conv_states.dtype
    layer.conv_states = layer.conv_states.to(hidden_states.dtype)
    result = SHORT_CONV_FORWARD(module, hidden_states, past_key_values, cache_position, attention_mask, valid_mask)
    layer.conv_states = layer.conv_states.to(storage_dtype)
    return result


@contextlib.contextmanager
def fp16_export() -> Iterator[None]:
    transformers.AttentionInterface.register("lrt_transposed_attention", fp16_cache_attention)
    patch.Lfm3LiteRTShortConv.forward = fp16_conv_forward
    try:
        yield
    finally:
        patch.Lfm3LiteRTShortConv.forward = SHORT_CONV_FORWARD
        transformers.AttentionInterface.register("lrt_transposed_attention", attention.transposed_attention)


def validate_bundle(path: pathlib.Path) -> None:
    """Inspect metadata and every graph signature; changing a token limit is insufficient."""
    header = litertlm_peek.read_litertlm_header(str(path), sys.stdout)
    sections = header.SectionMetadata()
    kinds = [sections.Objects(index).DataType() for index in range(sections.ObjectsLength())]
    for required in (
        schema.AnySectionDataType.LlmMetadataProto,
        schema.AnySectionDataType.ExecutorMetadataProto,
        schema.AnySectionDataType.TFLiteModel,
    ):
        if kinds.count(required) != 1:
            raise ValueError("Expected exactly one graph and both metadata sections")
    with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for index in range(sections.ObjectsLength()):
            section = sections.Objects(index)
            if section.DataType() == schema.AnySectionDataType.LlmMetadataProto:
                if section.EndOffset() - section.BeginOffset() > 1024 * 1024:
                    raise ValueError("LLM metadata exceeds 1 MiB")
                metadata = llm_metadata_pb2.LlmMetadata.FromString(data[section.BeginOffset() : section.EndOffset()])
                if (
                    metadata.max_num_tokens != CONTEXT_LENGTH
                    or not metadata.llm_model_type.HasField("generic_model")
                    or not metadata.supports_function_calling
                    or not metadata.supports_thinking
                ):
                    raise ValueError("Bundle lost its 64K LFM3 tool/thinking metadata")
                validate_template(metadata.jinja_prompt_template)
            elif section.DataType() == schema.AnySectionDataType.TFLiteModel:
                model = tflite.Model.GetRootAs(data, section.BeginOffset())
                if model.SignatureDefsLength() == 0:
                    raise ValueError("Graph has no prefill/decode signatures")
                for graph_index in range(model.SubgraphsLength()):
                    graph = model.Subgraphs(graph_index)
                    if any(graph.Tensors(i).Type() == tflite.TensorType.INT4 for i in range(graph.TensorsLength())):
                        raise ValueError("INT8 bundle contains an INT4 tensor")
                for signature_index in range(model.SignatureDefsLength()):
                    signature = model.SignatureDefs(signature_index)
                    graph = model.Subgraphs(signature.SubgraphIndex())
                    int8_count = sum(
                        graph.Tensors(i).Type() == tflite.TensorType.INT8 for i in range(graph.TensorsLength())
                    )
                    expected_int8 = 155 if signature.SignatureKey() == b"decode" else 150
                    if int8_count != expected_int8:
                        raise ValueError(f"Expected {expected_int8} INT8 matrix weights; found {int8_count}")
                    embeddings = [
                        graph.Tensors(i)
                        for i in range(graph.TensorsLength())
                        if graph.Tensors(i).ShapeLength() == 2
                        and tuple(graph.Tensors(i).ShapeAsNumpy()) == (128000, 2048)
                    ]
                    if (
                        not embeddings
                        or any(weight.Type() != tflite.TensorType.INT8 for weight in embeddings)
                        or len({weight.Buffer() for weight in embeddings}) != 1
                    ):
                        raise ValueError("Expected one INT8 token embedding matrix")
                    for operator_index in range(graph.OperatorsLength()):
                        operator = graph.Operators(operator_index)
                        code = model.OperatorCodes(operator.OpcodeIndex()).BuiltinCode()
                        if code == tflite.BuiltinOperator.FULLY_CONNECTED:
                            weight = graph.Tensors(operator.Inputs(1))
                            if weight.Type() != tflite.TensorType.INT8:
                                raise ValueError("Fully connected matrix weight is not INT8")
                    counts = {"k": 0, "v": 0, "c": 0}
                    for input_index in range(signature.InputsLength()):
                        entry = signature.Inputs(input_index)
                        name = entry.Name().decode().removeprefix("kv_cache_")
                        if name.startswith(("k_", "v_", "c_")):
                            tensor = graph.Tensors(entry.TensorIndex())
                            if tensor.Type() != tflite.TensorType.FLOAT16:
                                raise ValueError(f"Cache {name} is not FP16")
                            if name.startswith(("k_", "v_")):
                                shape = [tensor.Shape(i) for i in range(tensor.ShapeLength())]
                                if CONTEXT_LENGTH not in shape:
                                    raise ValueError(f"Cache {name} cannot hold 64K tokens: {shape}")
                            counts[name[0]] += 1
                    if counts != {"k": 11, "v": 11, "c": 13}:
                        raise ValueError(f"Unexpected cache inputs: {counts}")
                    for output_index in range(signature.OutputsLength()):
                        entry = signature.Outputs(output_index)
                        if entry.Name().decode().startswith("kv_cache_"):
                            if graph.Tensors(entry.TensorIndex()).Type() != tflite.TensorType.FLOAT16:
                                raise ValueError("Cache output is not FP16")
    print("Validated INT8 matrix weights, 65,536-token FP16 caches and structured tool metadata", flush=True)


def export_model(snapshot: pathlib.Path, output_dir: pathlib.Path, options: dict[str, object]) -> None:
    model, weights_loader = loader.load_lfm3_for_export(str(snapshot))
    tokenizer = transformers.AutoTokenizer.from_pretrained(str(snapshot), trust_remote_code=True)
    artifacts = export_lib.SourceModelArtifacts(
        model=model,
        model_config=model.config,
        text_model_config=model.config,
        tokenizer=tokenizer,
        weights_loader=weights_loader,
    )
    with (
        mock.patch.object(export_lib, "load_model", return_value=artifacts),
        fp16_export(),
    ):
        export.export(
            model=str(snapshot), output_dir=str(output_dir), model_extension=extension.Lfm3Extension(), **options
        )


def finalize_bundle(bundle: pathlib.Path, output_dir: pathlib.Path) -> None:
    """Validate a completed conversion and record its inputs and current workflow."""
    target = output_dir / OUTPUT_NAME
    if target.exists():
        raise ValueError(f"Refusing to overwrite {target}")
    validate_bundle(bundle)
    write_json(
        output_dir / "manifest.json",
        {
            "model": MODEL_ID,
            "model_revision": MODEL_REVISION,
            "exporter_revision": EXPORTER_REVISION,
            "extension_revision": EXTENSION_REVISION,
            "file": target.name,
            "sha256": sha256_file(bundle),
            "size_bytes": bundle.stat().st_size,
            "context_length": CONTEXT_LENGTH,
            "cache_dtype": "float16",
            "weights": "Per-channel INT8 matrix weights; convolution and normalization constants FP32",
            "template_sha256": sha256_file(TEMPLATE_PATH),
            "workflow_script_sha256": sha256_file(pathlib.Path(__file__)),
            "conversion_inputs_sha256": {
                name: sha256_file(output_dir / name)
                for name in ("chat_template.jinja", "export_options.json", "llm_metadata.pbtext", "quantization.json")
            },
            "extension_files_sha256": {
                path.name: sha256_file(path)
                for path in sorted(pathlib.Path(__file__).with_name("lfm3_export").glob("*.py"))
            },
            "lock_sha256": sha256_file(pathlib.Path(__file__ + ".lock")),
            "validation": "INT8 weights, FP16 cache shapes and metadata inspected; inference not validated",
        },
    )
    bundle.replace(target)
    print(f"Exported candidate: {target}", flush=True)
    print("Inference quality and device performance are not validated by this export.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Export LFM3-1.7B to 64K INT8/FP16 LiteRT-LM.")
    parser.add_argument(
        "--output-dir",
        type=pathlib.Path,
        default=pathlib.Path("models/lfm3-1.7b-int8-64k-fp16"),
        help="Directory for exported files (default: %(default)s).",
    )
    parser.add_argument(
        "--prepare-only", action="store_true", help="Validate export inputs without downloading weights."
    )
    parser.add_argument("--validate-only", type=pathlib.Path, help="Inspect an existing export without converting it.")
    args = parser.parse_args()
    if args.validate_only is not None:
        validate_bundle(args.validate_only.resolve())
        return 0
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / OUTPUT_NAME
    if target.exists():
        parser.error(f"Refusing to overwrite {target}")
    snapshot, recipe_path, metadata_path = prepare(output_dir)
    options = {
        "cache_length": CONTEXT_LENGTH,
        "experimental_use_fp16": True,
        "keep_temporary_files": True,
        "litert_lm_llm_metadata_override": str(metadata_path),
        "prefill_lengths": [1, 4, 16, 64, 256],
        "quantization_recipe": str(recipe_path),
        "use_jinja_template": True,
        "jinja_chat_template_override": str(output_dir / "chat_template.jinja"),
        "tokenizer_path_override": str(snapshot / "tokenizer.json"),
        "externalize_embedder": False,
        "single_token_embedder": False,
        "use_v2": True,
    }
    write_json(output_dir / "export_options.json", options)
    write_json(
        output_dir / "environment.json",
        {distribution.metadata["Name"]: distribution.version for distribution in importlib.metadata.distributions()},
    )
    print(f"Prepared pinned 64K INT8/FP16 LFM3 export inputs in {output_dir}", flush=True)
    if args.prepare_only:
        return 0
    snapshot_download(MODEL_ID, revision=MODEL_REVISION, allow_patterns=["*.safetensors"])
    work = output_dir / "work"
    if work.exists():
        parser.error(f"Export work already exists at {work}; inspect it before choosing a fresh output directory")
    export_model(snapshot, work, options)
    bundles = list(work.glob("*.litertlm"))
    if len(bundles) != 1:
        raise RuntimeError(f"Expected one exported bundle, found {bundles}")
    bundle = bundles[0]
    finalize_bundle(bundle, output_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
