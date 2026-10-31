#!/usr/bin/env -S uv run --locked --script
# Package and upload a validated LFM3 LiteRT export to a public Hugging Face model repository.
# /// script
# requires-python = ">=3.12"
# dependencies = ["huggingface-hub==2.4.0"]
# ///

import argparse
import hashlib
import json
import pathlib
import sys
from dataclasses import dataclass, fields

from huggingface_hub import CommitOperationAdd, HfApi, ModelCard, ModelCardData, hf_hub_url, login
from huggingface_hub.hf_api import RepoFile
from huggingface_hub.utils import HfHubHTTPError, validate_repo_id

RELEASE_FILES = (
    "LICENSE",
    "README.md",
    "chat_template.jinja",
    "environment.json",
    "export_options.json",
    "llm_metadata.pbtext",
    "manifest.json",
    "quantization.json",
)


@dataclass
class ExportManifest:
    model: str
    file: str
    sha256: str
    size_bytes: int
    context_length: int
    cache_dtype: str
    weights: str
    conversion_inputs_sha256: dict[str, str]


def verify_file(path: pathlib.Path, expected_sha256: str) -> None:
    with path.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != expected_sha256:
        raise ValueError(f"SHA-256 mismatch for {path}: expected {expected_sha256}, got {actual}")


def prepare_release(output_dir: pathlib.Path) -> ExportManifest:
    data = json.loads((output_dir / "manifest.json").read_text(encoding="utf-8"))
    manifest = ExportManifest(**{field.name: data[field.name] for field in fields(ExportManifest)})
    if pathlib.Path(manifest.file).name != manifest.file or not manifest.file.endswith(".litertlm"):
        raise ValueError("Manifest must name a .litertlm file directly inside the export directory")
    bundle = output_dir / manifest.file
    if bundle.stat().st_size != manifest.size_bytes:
        raise ValueError(f"Size mismatch for {bundle}")
    print(f"Verifying {bundle} ({manifest.size_bytes:,} bytes)...", flush=True)
    verify_file(bundle, manifest.sha256)
    expected_inputs = {"chat_template.jinja", "export_options.json", "llm_metadata.pbtext", "quantization.json"}
    if set(manifest.conversion_inputs_sha256) != expected_inputs:
        raise ValueError("Manifest must contain hashes for all four conversion inputs")
    for name, digest in manifest.conversion_inputs_sha256.items():
        verify_file(output_dir / name, digest)
    for name in RELEASE_FILES:
        if name != "README.md" and not (output_dir / name).is_file():
            raise ValueError(f"Missing release file: {output_dir / name}")

    card = create_model_card(manifest)
    card.validate()
    card.save(output_dir / "README.md")
    return manifest


def create_model_card(manifest: ExportManifest) -> ModelCard:
    metadata = ModelCardData(
        base_model=manifest.model,
        base_model_relation="quantized",
        library_name="litert-lm",
        license="other",
        license_name="lfm1.0",
        license_link="LICENSE",
        pipeline_tag="text-generation",
        tags=["android", "int8", "litert-lm", "litertlm"],
    )
    return ModelCard(f"""---
{metadata.to_yaml()}
---

# LFM3-1.7B — LiteRT-LM INT8

LiteRT-LM conversion of [{manifest.model}](https://huggingface.co/{manifest.model}) by Liquid AI.

- Bundle: [{manifest.file}]({manifest.file}), including tokenizer and chat template.
- Weights: {manifest.weights}.
- Context capacity: {manifest.context_length:,} tokens.
- KV and recurrent state: `{manifest.cache_dtype}`.
- Size: {manifest.size_bytes / 1_000_000_000:.1f} GB.

Tool calls use LFM3's trained XML-like format and require client-side parsing,
as implemented in [Go Mode](https://github.com/maruel/gomode).

[Export script](https://github.com/maruel/gomode/blob/main/scripts/export_lfm_litert.py).

## License

[LFM Open License v1.0](LICENSE). The checkpoint is quantized and the chat template
is adapted for LiteRT conversation replay.
""")


def release_matches(api: HfApi, repo_id: str, revision: str, additions: list[CommitOperationAdd]) -> bool:
    """Compare every release file at one immutable revision, including SDK no-op results."""
    remote = {
        entry.path: entry
        for entry in api.get_paths_info(repo_id, [op.path_in_repo for op in additions], revision=revision)
        if isinstance(entry, RepoFile)
    }
    for addition in additions:
        entry = remote.get(addition.path_in_repo)
        if entry is None or entry.size != addition.upload_info.size:
            return False
        if entry.lfs is not None:
            if entry.lfs.sha256 != addition.upload_info.sha256.hex():
                return False
        else:
            digest = hashlib.sha1(f"blob {addition.upload_info.size}\0".encode())
            with addition.as_file() as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if entry.blob_id != digest.hexdigest():
                return False
    return True


@dataclass
class Release:
    output_dir: pathlib.Path
    manifest: ExportManifest
    repo_id: str


def publish_release(api: HfApi, release: Release) -> None:
    print(f"Publishing to {release.repo_id}", flush=True)
    names = sorted((*RELEASE_FILES, release.manifest.file))
    info = api.model_info(release.repo_id)
    if info.private or info.gated:
        raise ValueError("The Android app requires a public, ungated model repository")
    additions = [CommitOperationAdd(path_in_repo=name, path_or_fileobj=release.output_dir / name) for name in names]
    bundle = next(op for op in additions if op.path_in_repo == release.manifest.file)
    if bundle.upload_info.sha256.hex() != release.manifest.sha256:
        raise ValueError("The bundle changed after manifest verification; retry with a stable export directory")
    if release_matches(api, release.repo_id, info.sha, additions):
        # Avoid the SDK's no-op path, which returns latest HEAD rather than the captured revision.
        revision = info.sha
        print(f"Release already published at {revision}.")
    else:
        # Override remote ignore rules for this explicit release allowlist without changing .gitignore.
        api.preupload_lfs_files(release.repo_id, additions, revision="main", free_memory=False, gitignore_content="")
        commit = api.create_commit(
            repo_id=release.repo_id,
            repo_type="model",
            parent_commit=info.sha,
            commit_message=f"Publish {release.manifest.file} ({release.manifest.sha256[:12]})",
            operations=additions,
        )
        revision = commit.oid
        # Also covers a concurrent identical upload that makes the SDK return a no-op result.
        if not release_matches(api, release.repo_id, revision, additions):
            raise ValueError(f"Published release verification failed at {revision}; download pins were not emitted")
        print(f"Published: {commit.commit_url}")
    print(f"Download URL: {hf_hub_url(release.repo_id, release.manifest.file, revision=revision)}")
    print(f"Revision: {revision}\nSize: {release.manifest.size_bytes}\nSHA-256: {release.manifest.sha256}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Package and upload available 32K and 64K LFM3 exports to Hugging Face."
    )
    parser.add_argument(
        "repo_id",
        nargs="?",
        help="Repository name or OWNER/NAME for --output-dir (default: LFM3-1.7B-LiteRT-INT8-CONTEXTK).",
    )
    parser.add_argument(
        "--output-dir",
        type=pathlib.Path,
        help="Upload only this export directory (default: available 32K and 64K exports under models/).",
    )
    args = parser.parse_args()
    if args.repo_id is not None:
        validate_repo_id(args.repo_id)
        if args.output_dir is None:
            parser.error("a custom repository requires --output-dir to select one export")
    if args.output_dir is not None:
        output_dirs = [args.output_dir.resolve()]
    else:
        output_dirs = []
        for context in (32, 64):
            directory = pathlib.Path(f"models/lfm3-1.7b-int8-{context}k-fp16").resolve()
            if (directory / f"LFM3-1.7B_int8_{context}k_fp16.litertlm").is_file():
                output_dirs.append(directory)
            else:
                print(f"Skipping missing {context}K export: {directory}", flush=True)
        if not output_dirs:
            parser.error("no completed 32K or 64K exports found; run make android-export-lfm first")
    # Validate every local release before authentication or any upload.
    releases = []
    for output_dir in output_dirs:
        manifest = prepare_release(output_dir)
        repo_id = args.repo_id or f"LFM3-1.7B-LiteRT-INT8-{manifest.context_length // 1024}K"
        releases.append(Release(output_dir, manifest, repo_id))
        print(f"Prepared release for {repo_id}: {output_dir}", flush=True)
    # Reuse cached credentials or HF_TOKEN; login prompts only when credentials are missing.
    login()
    api = HfApi()
    destination = "your HF account"
    try:
        identity = api.whoami()
        # Check access to all destinations before uploading model data.
        for release in releases:
            if "/" not in release.repo_id:
                release.repo_id = f"{identity['name']}/{release.repo_id}"
            validate_repo_id(release.repo_id)
            destination = release.repo_id
            print(f"Checking write access to {destination}...", flush=True)
            api.create_repo(destination, repo_type="model", private=False, exist_ok=True)
            api.auth_check(destination, repo_type="model", write=True)
    except HfHubHTTPError as error:
        if error.response.status_code not in (401, 403):
            raise
        parser.exit(
            1,
            f"HF credentials cannot authenticate, create, or write to {destination}. "
            "Use a write-enabled token scoped to this repository and an account with namespace access. "
            "Reauthenticate with `hf auth login --force`; if HF_TOKEN is set, update or unset it.\n",
        )
    for release in releases:
        publish_release(api, release)
    return 0


if __name__ == "__main__":
    sys.exit(main())
