"""Windows Job Object containment for the sandbox worker process tree.

The job kills every descendant when the API closes it (timeout, crash, exit),
caps job-wide memory and the number of live processes. On other platforms this is
a no-op and `contain` reports `applied=False`; the wall-clock kill in the client
still applies.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass

MEMORY_LIMIT_BYTES = 3 * 1024 ** 3
ACTIVE_PROCESS_LIMIT = 64

_KILL_ON_JOB_CLOSE = 0x2000
_JOB_MEMORY = 0x200
_ACTIVE_PROCESS = 0x8
_EXTENDED_LIMIT_INFO = 9


@dataclass
class Containment:
    applied: bool
    detail: str = ""
    handle: int | None = None

    def close(self) -> None:
        if self.handle:
            import ctypes
            ctypes.windll.kernel32.CloseHandle(self.handle)  # kills any remaining descendants
            self.handle = None


def contain(process) -> Containment:
    if sys.platform != "win32":
        return Containment(False, "job objects are Windows-only")
    try:
        import ctypes
        from ctypes import wintypes

        class BASIC(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]

        class IO(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

        class EXTENDED(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BASIC), ("IoInfo", IO), ("ProcessMemoryLimit", ctypes.c_size_t),
                        ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                        ("PeakJobMemoryUsed", ctypes.c_size_t)]

        kernel32 = ctypes.windll.kernel32
        kernel32.CreateJobObjectW.restype = wintypes.HANDLE
        kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        kernel32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        job = kernel32.CreateJobObjectW(None, None)
        if not job:
            return Containment(False, "CreateJobObject failed")
        info = EXTENDED()
        info.BasicLimitInformation.LimitFlags = _KILL_ON_JOB_CLOSE | _JOB_MEMORY | _ACTIVE_PROCESS
        info.BasicLimitInformation.ActiveProcessLimit = ACTIVE_PROCESS_LIMIT
        info.JobMemoryLimit = MEMORY_LIMIT_BYTES
        if not kernel32.SetInformationJobObject(job, _EXTENDED_LIMIT_INFO, ctypes.byref(info), ctypes.sizeof(info)):
            kernel32.CloseHandle(job)
            return Containment(False, "SetInformationJobObject failed")
        if not kernel32.AssignProcessToJobObject(job, wintypes.HANDLE(int(process._handle))):
            kernel32.CloseHandle(job)
            return Containment(False, "AssignProcessToJobObject failed")
        return Containment(True, f"kill-on-close, {MEMORY_LIMIT_BYTES >> 20} MiB, {ACTIVE_PROCESS_LIMIT} processes", int(job))
    except Exception as exc:  # containment is best-effort and reported, never silent
        return Containment(False, f"job object error: {type(exc).__name__}")


class ContainmentError(RuntimeError):
    """Raised when a worker cannot be placed in its Job Object; the worker is never left running."""


def _resume(process) -> None:
    import ctypes
    from ctypes import wintypes

    ntdll = ctypes.windll.ntdll
    ntdll.NtResumeProcess.argtypes = [wintypes.HANDLE]
    status = ntdll.NtResumeProcess(wintypes.HANDLE(int(process._handle)))
    if status != 0:
        raise ContainmentError(f"NtResumeProcess failed (0x{status & 0xFFFFFFFF:08x})")


def start_contained(command, **popen_kwargs):
    """Start `command` suspended, put it in a Job Object, then resume: no window in which it can escape.

    On Windows a failure to contain kills the process and raises ContainmentError (fail closed).
    Elsewhere the process starts normally and containment reports applied=False.
    """
    import subprocess

    if sys.platform != "win32":
        return subprocess.Popen(command, **popen_kwargs), Containment(False, "job objects are Windows-only")
    flags = popen_kwargs.pop("creationflags", 0) | 0x00000004  # CREATE_SUSPENDED
    process = subprocess.Popen(command, creationflags=flags, **popen_kwargs)
    containment = contain(process)
    if not containment.applied:
        process.kill()
        process.wait()
        raise ContainmentError(containment.detail)
    try:
        _resume(process)
    except Exception:
        containment.close()
        process.kill()
        process.wait()
        raise
    return process, containment
