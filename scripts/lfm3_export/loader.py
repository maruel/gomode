# Loads pinned LFM3 architecture with streamed checkpoint weights for LiteRT export.
# Adapted from hf-to-litertlm f3b7baad40ec0f6a4b413912a68497bf8c5d8aa3, Apache-2.0; see LICENSE.
"""Keep parameters on meta and non-persistent RoPE buffers on CPU.

Converter V2 streams each tensor from the pinned safetensors checkpoint while
serializing it. Constructing with torch.device('meta') would also lose the RoPE
buffers, which are not checkpoint weights, so use the exporter's parameter-only
meta initialization instead.
"""

import json
from pathlib import Path

import torch
import transformers
from litert_torch.generative.export_hf.core import weights_loader as weights_loader_lib
from transformers import dynamic_module_utils, initialization


def load_lfm3_config(code_dir: str) -> transformers.PretrainedConfig:
    config_cls = dynamic_module_utils.get_class_from_dynamic_module("configuration_lfm3.Lfm3Config", code_dir)
    config = json.loads((Path(code_dir) / "config.json").read_text(encoding="utf-8"))
    if config["model_type"] != "lfm3":
        raise ValueError("Expected an LFM3 checkpoint")
    # Checkpoint config records its training dtype. Export compute and rotary
    # buffers stay FP32; only state storage uses FP16.
    config["dtype"] = "float32"
    return config_cls(**config)


def load_lfm3_for_export(code_dir: str) -> tuple[torch.nn.Module, weights_loader_lib.WeightsLoader]:
    config = load_lfm3_config(code_dir)
    model_cls = dynamic_module_utils.get_class_from_dynamic_module("modeling_lfm3.Lfm3ForCausalLM", code_dir)
    with initialization.no_init_weights(), weights_loader_lib.init_params_on_meta():
        model = model_cls(config)
    if not config.tie_word_embeddings:
        raise ValueError("Expected tied LFM3 embedding and output weights")
    model.lm_head.weight = model.model.embed_tokens.weight
    weights_loader = weights_loader_lib.HFCheckpointWeightsLoader(code_dir, tie_word_embeddings=True)
    weights_loader_lib.load_persistent_buffers_(model, weights_loader)
    model.generation_config = transformers.GenerationConfig.from_pretrained(code_dir)
    return model.eval(), weights_loader
