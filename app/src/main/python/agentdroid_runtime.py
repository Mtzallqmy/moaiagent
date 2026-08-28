"""Restricted Python execution helper for AgentDroid's embedded runtime.

This is an application-level guard, not an OS security boundary. AgentDroid still requires
permission before invoking it, and network/process creation are disabled for ordinary runs.
"""

import builtins
import contextlib
import io
import json
import os
import re
import sys
import time
import traceback

_BLOCKED_IMPORT_ROOTS = {"android", "java", "ctypes", "socket", "subprocess", "multiprocessing"}
_PACKAGE_SPEC = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*(?:\[[A-Za-z0-9_,.-]+\])?(?:==[A-Za-z0-9._+-]+)?$")


def version():
    return sys.version.split()[0]


def _inside(root, path):
    root_real = os.path.realpath(root)
    target = os.path.realpath(path if os.path.isabs(path) else os.path.join(root_real, path))
    return target == root_real or target.startswith(root_real + os.sep)


def _run(code, workspace, timeout_seconds):
    root = os.path.realpath(workspace)
    if not os.path.isdir(root):
        raise ValueError("Workspace does not exist")
    timeout_seconds = max(0.05, min(float(timeout_seconds), 60.0))
    deadline = time.monotonic() + timeout_seconds
    stdout = io.StringIO(); stderr = io.StringIO()
    package_dir = os.path.join(root, ".agentdroid", "python-packages")

    original_open = builtins.open
    original_io_open = io.open
    original_import = builtins.__import__
    original_cwd = os.getcwd()
    original_system = os.system
    original_popen = getattr(os, "popen", None)
    original_chdir = os.chdir
    added_package_path = False

    def safe_open(file, *args, **kwargs):
        if isinstance(file, int):
            raise PermissionError("Raw file descriptors are not available to Agent Python")
        path = os.fspath(file)
        if not _inside(root, path):
            raise PermissionError("Python file access is limited to the workspace")
        resolved = os.path.realpath(path if os.path.isabs(path) else os.path.join(root, path))
        return original_open(resolved, *args, **kwargs)

    def safe_chdir(path):
        if not _inside(root, path):
            raise PermissionError("Python cwd is limited to the workspace")
        original_chdir(os.path.realpath(path if os.path.isabs(path) else os.path.join(root, path)))

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        root_name = name.split(".", 1)[0]
        if root_name in _BLOCKED_IMPORT_ROOTS:
            raise PermissionError("Import blocked by AgentDroid runtime policy: " + root_name)
        return original_import(name, globals, locals, fromlist, level)

    def blocked_process(*args, **kwargs):
        raise PermissionError("Process creation is blocked; use AgentDroid run_command instead")

    def trace(frame, event, arg):
        if time.monotonic() > deadline:
            raise TimeoutError("Python execution exceeded the AgentDroid time limit")
        return trace

    result = {"stdout": "", "stderr": "", "exitCode": 0, "error": None, "timedOut": False}
    try:
        original_chdir(root)
        if os.path.isdir(package_dir) and package_dir not in sys.path:
            sys.path.insert(0, package_dir); added_package_path = True
        builtins.open = safe_open; io.open = safe_open; builtins.__import__ = guarded_import
        os.chdir = safe_chdir; os.system = blocked_process
        if original_popen is not None: os.popen = blocked_process
        for name in dir(os):
            if name.startswith("exec") or name.startswith("spawn"):
                try: setattr(os, name, blocked_process)
                except Exception: pass
        sys.settrace(trace)
        globals_dict = {"__name__": "__main__", "__file__": "<agentdroid>"}
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            exec(code if not isinstance(code, str) else compile(code, "<agentdroid>", "exec"), globals_dict, globals_dict)
    except TimeoutError as exc:
        result["exitCode"] = 124; result["error"] = str(exc); result["timedOut"] = True
    except BaseException as exc:
        result["exitCode"] = 1; result["error"] = "".join(traceback.format_exception_only(type(exc), exc)).strip()
    finally:
        sys.settrace(None)
        builtins.open = original_open; io.open = original_io_open; builtins.__import__ = original_import
        os.chdir = original_chdir; os.system = original_system
        if original_popen is not None: os.popen = original_popen
        original_chdir(original_cwd)
        if added_package_path and package_dir in sys.path: sys.path.remove(package_dir)
        result["stdout"] = stdout.getvalue(); result["stderr"] = stderr.getvalue()
    return result


def run_code(code, workspace, timeout_seconds=30.0):
    if not isinstance(code, str) or not code.strip() or len(code.encode("utf-8")) > 512 * 1024:
        raise ValueError("Python code is empty or too large")
    return json.dumps(_run(code, workspace, timeout_seconds), ensure_ascii=False)


def run_file(relative_path, workspace, timeout_seconds=30.0):
    if not _inside(workspace, relative_path):
        raise PermissionError("Python script path is outside the workspace")
    path = os.path.realpath(os.path.join(workspace, relative_path))
    with open(path, "r", encoding="utf-8") as handle:
        code = handle.read(512 * 1024 + 1)
    if len(code.encode("utf-8")) > 512 * 1024:
        raise ValueError("Python script is too large")
    return json.dumps(_run(compile(code, relative_path, "exec"), workspace, timeout_seconds), ensure_ascii=False)


def install_package(spec, workspace):
    """Best-effort pip into a workspace-local target after AgentDroid EXTERNAL permission."""
    if not _PACKAGE_SPEC.fullmatch(spec or ""):
        raise ValueError("Package spec must be a simple pinned or unpinned package name")
    target = os.path.realpath(os.path.join(workspace, ".agentdroid", "python-packages"))
    if not _inside(workspace, target):
        raise PermissionError("Package target escapes workspace")
    os.makedirs(target, exist_ok=True)
    try:
        from pip._internal.cli.main import main as pip_main
    except Exception as exc:
        return json.dumps({"exitCode": 1, "stdout": "", "stderr": "", "error": "pip unavailable: " + str(exc)})
    out = io.StringIO(); err = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        exit_code = int(pip_main(["install", "--disable-pip-version-check", "--no-input", "--target", target, spec]) or 0)
    return json.dumps({"exitCode": exit_code, "stdout": out.getvalue(), "stderr": err.getvalue(), "error": None if exit_code == 0 else "pip install failed"}, ensure_ascii=False)
