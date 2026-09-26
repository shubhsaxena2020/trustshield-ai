import subprocess
import sys
import time

import pytest

from services.analysis.web_sandbox.jobobject import contain

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="Job Objects are Windows-only")

CHILD = (
    "import subprocess, sys, time, pathlib\n"
    "kid = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])\n"
    "pathlib.Path(sys.argv[1]).write_text(str(kid.pid))\n"
    "time.sleep(120)\n"
)


def _alive(pid: int) -> bool:
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return str(pid) in out


def test_closing_the_job_kills_the_whole_process_tree(tmp_path):
    pidfile = tmp_path / "kid.pid"
    parent = subprocess.Popen([sys.executable, "-c", CHILD, str(pidfile)])
    containment = contain(parent)
    assert containment.applied, containment.detail
    for _ in range(100):
        if pidfile.exists() and pidfile.read_text():
            break
        time.sleep(0.1)
    kid = int(pidfile.read_text())
    assert _alive(kid) and parent.poll() is None
    containment.close()
    for _ in range(50):
        if not _alive(kid) and parent.poll() is not None:
            break
        time.sleep(0.1)
    assert parent.poll() is not None
    assert not _alive(kid)
