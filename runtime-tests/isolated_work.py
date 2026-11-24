"""Allocate a fresh test tree; on Windows delete it only after the native worker exits."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile


def isolated_work(script: str, prefix: str) -> Path:
    # Per-script private handoff. The worker gets exactly the parent's fresh directory.
    marker = 'NIUMA_RIME_PROBE_' + Path(script).stem.upper()
    provided = os.environ.pop(marker, None)
    if provided is not None:
        target = Path(provided).resolve(strict=True)
        if target.parent != Path(tempfile.gettempdir()).resolve() or not target.name.startswith(prefix) or any(target.iterdir()):
            raise RuntimeError('Invalid or nonempty probe work directory')
        return target
    if '--keep-work' in sys.argv:
        target = Path(tempfile.mkdtemp(prefix=prefix))
        print(f'Retaining probe work directory: {target}', flush=True)
        return target
    with tempfile.TemporaryDirectory(prefix=prefix) as work:
        code = subprocess.call([sys.executable, str(Path(script).resolve()), *sys.argv[1:]],
                               env={**os.environ, marker: work})
    raise SystemExit(code)
