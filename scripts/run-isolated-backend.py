#!/usr/bin/env python3
"""Start a disposable isolated GymTracka backend for Android real-backend tests.

Uses only the Python standard library. Provisions, in order:

1. Refuses when the config path already exists (file, symlink, or directory),
   before provisioning anything, so a second run can never destroy the first
   run's config or any pre-existing file. Also refuses an occupied HTTP port.
2. Validates the backend's pinned shared inputs (existing checksum file).
3. Builds the backend JAR under the existing Maven lock (unless --no-build).
4. Starts a disposable labelled ``postgres:16-alpine`` container on a loopback
   port with generated test-only credentials (never personal databases or
   environment files).
5. Starts the real backend JAR against that database with generated test-only
   keys, waits for Flyway plus HTTP readiness, then inserts the run-guard row.
6. Publishes a mode-600 ephemeral JSON config with exclusive creation and
   waits in the foreground, propagating the backend's exit status.

Cleanup on Ctrl+C, SIGTERM, startup failure, or backend exit removes ONLY this
run's own published config (verified by device/inode identity), child backend
process, and container. It never kills port occupants or unrelated services,
never loads ``dev.env``, and takes no database inputs.

Usage:
    python3 scripts/run-isolated-backend.py [--backend-root PATH]
        [--config-path PATH] [--port 18080] [--no-build]

Then, separately (this script never runs Gradle):
    ./gradlew :app:testRealBackend \\
        -Pgymtracka.realBackendConfig=/tmp/gymtracka-real-backend/config.json
    ./gradlew :app:testDebugUnitTest   # offline; needs no backend or config

Regression tests (stdlib only, no Docker/backend needed):
    python3 -m unittest discover -s scripts/tests -p 'test_*.py'
"""

import argparse
import base64
import json
import os
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

POSTGRES_IMAGE = "postgres:16-alpine"
DISPOSABLE_DATABASE = "gymtracka_android_test"
DISPOSABLE_USER = "android_test"
BUILD_LOCK = "/tmp/gymtracka-backend-build.lock"


class ConfigExistsError(RuntimeError):
    """The config path is already occupied; nothing was provisioned."""


class PortOccupiedError(RuntimeError):
    """The requested loopback port already has an occupant; refusing to proceed."""


def run(*argv, **kwargs):
    kwargs.setdefault("timeout", 120)
    return subprocess.run(argv, check=True, **kwargs)


def claim_config_path(config_path):
    """Refuse an occupied config path before provisioning anything.

    Any pre-existing file, symlink, or directory raises ConfigExistsError and
    is left untouched.
    """
    if os.path.lexists(config_path):
        raise ConfigExistsError(
            f"Refusing occupied config path: {config_path}")


def check_port_free(port):
    """Fail fast when the loopback port already has an occupant.

    A successful connect proves occupancy; refusal/timeout means free. Never
    touches the occupant.
    """
    probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        probe.settimeout(2)
        probe.connect(("127.0.0.1", port))
    except OSError:
        return
    finally:
        probe.close()
    raise PortOccupiedError(
        f"Refusing occupied loopback port {port}; stop its occupant first")


def publish_config(config_path, config):
    """Create the mode-600 config exclusively; return its identity.

    O_EXCL guarantees a concurrent creator wins instead of being truncated.
    """
    fd = os.open(config_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w") as handle:
            json.dump(config, handle)
    except BaseException:
        try:
            os.unlink(config_path)
        except OSError:
            pass
        raise
    identity = os.stat(config_path)
    return (identity.st_dev, identity.st_ino)


def unpublish_config(config_path, identity):
    """Remove the config only when it is still this run's own published file.

    A non-matching identity, symlink, directory, or missing file is left alone
    and reported as False.
    """
    if identity is None:
        return False
    try:
        if os.path.islink(config_path) or not os.path.isfile(config_path):
            return False
        current = os.stat(config_path)
        if (current.st_dev, current.st_ino) != identity:
            return False
        os.unlink(config_path)
        return True
    except OSError:
        return False


def wait_until(deadline_seconds, interval, description, probe):
    deadline = time.monotonic() + deadline_seconds
    while True:
        if probe():
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(description + " did not become ready")
        time.sleep(interval)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-root", default="/home/dragos/IdeaProjects/gt-backend")
    parser.add_argument("--config-path", default="/tmp/gymtracka-real-backend/config.json")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--no-build", action="store_true",
                        help="reuse the existing backend JAR instead of rebuilding")
    args = parser.parse_args(argv)

    backend_root = Path(args.backend_root)
    config_path = args.config_path
    if not 1 <= args.port <= 65535:
        print("error: --port must be 1-65535", file=sys.stderr)
        return 2

    # Ownership guards before any provisioning.
    try:
        claim_config_path(config_path)
        check_port_free(args.port)
    except (ConfigExistsError, PortOccupiedError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if not backend_root.is_dir():
        print(f"error: backend root is not a directory: {backend_root}", file=sys.stderr)
        return 2

    run_id = str(uuid.uuid4())
    container = "gymtracka-android-test-" + run_id
    password = secrets.token_urlsafe(32)
    auth_key = base64.b64encode(secrets.token_bytes(32)).decode()
    proc = None
    config_identity = None

    def stop(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    exit_code = 0
    try:
        # Pinned inputs, then the JAR under the shared Maven lock.
        run("sha256sum", "-c", "docs/shared-docs-inputs.sha256", cwd=backend_root)
        if not args.no_build:
            run("flock", BUILD_LOCK, "./mvnw", "-q", "-DskipTests", "package",
                cwd=backend_root, timeout=1800)
        jar = backend_root / "target/gtbackend-0.1.0-SNAPSHOT.jar"
        if not jar.is_file():
            raise RuntimeError(f"backend JAR missing: {jar}")

        # Disposable database. Secrets travel via the child process
        # environment (bare `-e NAME` inherits without appearing in argv).
        env = os.environ.copy()
        env.update(
            POSTGRES_USER=DISPOSABLE_USER,
            POSTGRES_PASSWORD=password,
            POSTGRES_DB=DISPOSABLE_DATABASE,
        )
        run("docker", "run", "--detach", "--rm", "--name", container,
            "--label", "gymtracka.android-test-run=" + run_id,
            "-p", "127.0.0.1::5432",
            "-e", "POSTGRES_USER", "-e", "POSTGRES_PASSWORD", "-e", "POSTGRES_DB",
            POSTGRES_IMAGE, stdout=subprocess.DEVNULL, env=env)

        def postgres_ready():
            ready = subprocess.run(
                ["docker", "exec", container, "pg_isready",
                 "-U", DISPOSABLE_USER, "-d", DISPOSABLE_DATABASE],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
            return ready.returncode == 0

        wait_until(120, 0.5, "Isolated PostgreSQL", postgres_ready)
        host_port = subprocess.check_output(
            ["docker", "port", container, "5432/tcp"], text=True, timeout=15,
        ).strip().rsplit(":", 1)[1]

        # Real backend against ONLY the disposable database.
        app_env = os.environ.copy()
        app_env.update(
            DB_URL=f"jdbc:postgresql://127.0.0.1:{host_port}/{DISPOSABLE_DATABASE}",
            DB_USER=DISPOSABLE_USER,
            DB_PASSWORD=password,
            GOOGLE_OAUTH_AUDIENCE="android-isolated-test",
            AUTH_ENCRYPTION_KEY=auth_key,
            ACCOUNT_DELETION_LEDGER_KEYS="",
            ACCOUNT_DELETION_PURGE_ENABLED="false",
            SYNC_HISTORY_CLEANUP_ENABLED="false",
            SYNC_SNAPSHOT_CLEANUP_ENABLED="false",
            AUTH_CLEANUP_INITIAL_DELAY_MS="3600000",
        )
        proc = subprocess.Popen(
            ["java", "-jar", str(jar),
             f"--server.port={args.port}", "--server.address=127.0.0.1"],
            cwd=backend_root, env=app_env)

        challenge_url = f"http://127.0.0.1:{args.port}/v1/auth/google/challenge"

        def http_ready():
            if proc.poll() is not None:
                raise RuntimeError("Isolated backend stopped during startup")
            try:
                request = urllib.request.Request(
                    challenge_url, data=b"{}",
                    headers={"Content-Type": "application/json"})
                with urllib.request.urlopen(request, timeout=2) as response:
                    return response.status == 200
            except (OSError, urllib.error.URLError):
                return False

        wait_until(180, 0.5, "Isolated backend", http_ready)

        # Guard row AFTER Flyway/HTTP readiness proves the schema migrated.
        guard_sql = ("CREATE TABLE gymtracka_android_test_guard (run_id uuid PRIMARY KEY); "
                     f"INSERT INTO gymtracka_android_test_guard VALUES ('{run_id}');")
        run("docker", "exec", "-i", container, "psql",
            "-U", DISPOSABLE_USER, "-d", DISPOSABLE_DATABASE,
            "-v", "ON_ERROR_STOP=1",
            input=guard_sql, text=True, stdout=subprocess.DEVNULL)

        # Ephemeral mode-600 config; values are generated test-only secrets.
        config_dir = os.path.dirname(os.path.abspath(config_path))
        os.makedirs(config_dir, exist_ok=True)
        config = {
            "baseUrl": f"http://127.0.0.1:{args.port}",
            "jdbcUrl": f"jdbc:postgresql://127.0.0.1:{host_port}/{DISPOSABLE_DATABASE}",
            "databaseUser": DISPOSABLE_USER,
            "databasePassword": password,
            "authKey": auth_key,
            "runId": run_id,
            "containerName": container,
        }
        try:
            config_identity = publish_config(config_path, config)
        except FileExistsError:
            raise ConfigExistsError(f"Refusing occupied config path: {config_path}")
        print(f"ISOLATED BACKEND READY: config={config_path}", flush=True)
        print("Run separately:", flush=True)
        print(f"  ./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig={config_path}",
              flush=True)
        print("  ./gradlew :app:testDebugUnitTest   # offline; needs no backend", flush=True)
        exit_code = proc.wait()
    except KeyboardInterrupt:
        print("Stopping isolated test backend", flush=True)
    except ConfigExistsError as exc:
        print(f"error: {exc}", file=sys.stderr)
        exit_code = 2
    finally:
        # Only this run's children: owned config by identity, owned process,
        # owned container. Never touches port occupants or unrelated services.
        unpublish_config(config_path, config_identity)
        if proc is not None and proc.poll() is None:
            proc.terminate()
            try:
                exit_code = proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                exit_code = proc.wait()
        subprocess.run(["docker", "rm", "-f", container],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return exit_code if isinstance(exit_code, int) else 0


if __name__ == "__main__":
    sys.exit(main())
