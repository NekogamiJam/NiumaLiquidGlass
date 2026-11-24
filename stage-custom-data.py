"""Stage only project-owned Niuma assets, without changing user dictionaries or using ADB."""
from pathlib import Path
import argparse
import hashlib
import json

ROOT = Path(__file__).resolve().parent
EXPECTED = {
    "niumawb_zc.dict.yaml": "09eff608e8831aa704005aa50ef69450d1729d752e3c212edcc894db9f51aaa9",
    "niumawb_fh.dict.yaml": "05b9981ba4aa20153012bc7762b137a0a29ed8bb8de7c17f2843a079a3466e2b",
}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    target = ROOT / "app/src/main/assets/shared"
    manifest_path = ROOT / "vendor/custom-assets.json"
    previous = {row["name"]: row["sha256"] for row in json.loads(manifest_path.read_text(encoding="utf-8"))} if manifest_path.exists() else {}
    sources = [ROOT / "input-data" / name for name in (
        "niumawb.dict.yaml", "niumawb_zc.dict.yaml", "niumawb_fh.dict.yaml"
    )] + [ROOT / "custom-data" / name for name in (
        "niuma_glass.schema.yaml", "niuma_glass.trime.yaml", "lua/niuma_fourcode.lua"
    )]
    # Check every file and collision before writing any destination.
    manifest = []
    for source in sources:
        sha = digest(source)
        if source.name in EXPECTED and sha != EXPECTED[source.name]:
            raise ValueError(f"Source dictionary changed: {source.name}")
        if source.name in EXPECTED:
            lines = source.read_text(encoding="utf-8-sig").splitlines()
            index = 1 if source.name == "niumawb_zc.dict.yaml" else 0
            for line in lines[lines.index("...") + 1:]:
                if not line or line.startswith("#"): continue
                code = line.split("\t")[index]
                if (code.startswith("z")) != (index == 0):
                    raise ValueError("Dictionary namespace no longer matches four-code policy")
        name = str(source.relative_to(ROOT / "custom-data")).replace("\\", "/") if source.is_relative_to(ROOT / "custom-data") else source.name
        destination = target / name
        if destination.exists() and digest(destination) not in {sha, previous.get(name)}:
            raise ValueError(f"Refusing to replace an unowned or externally modified asset: {destination}")
        manifest.append({"name": name, "source": str(source.relative_to(ROOT)), "sha256": sha, "bytes": source.stat().st_size})
    if not args.check_only:
        target.mkdir(parents=True, exist_ok=True)
        for source, row in zip(sources, manifest):
            destination = target / row["name"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists() or digest(destination) != row["sha256"]:
                destination.write_bytes(source.read_bytes())
            if digest(destination) != row["sha256"]:
                raise ValueError(f"Staged asset failed hash verification: {destination}")
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"check_only": args.check_only, "phone_access": False, "assets": manifest}, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
