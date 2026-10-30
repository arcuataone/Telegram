#!/usr/bin/env python3
"""Prepare a local Firebase client configuration without checking it into Git."""
import json
import os
from pathlib import Path
import re
import sys


def main():
    source = Path(sys.argv[1]).expanduser() if len(sys.argv) > 1 else Path.home() / ".config/qiqigram/google-services.json"
    destination = Path(__file__).resolve().parent.parent / "TMessagesProj_App/google-services.json"
    if not source.is_file():
        raise ValueError("Download your Firebase Android google-services.json and save it to " + str(source))
    try:
        data = source.read_bytes()
        config = json.loads(data)
    except (OSError, ValueError):
        raise ValueError("Cannot read Firebase configuration as JSON") from None
    if not isinstance(config, dict) or "private_key" in config or "client_email" in config:
        raise ValueError("Expected an Android client configuration, not a service-account key")
    project = config.get("project_info", {})
    if not isinstance(project, dict) or not project.get("project_id") or str(project["project_id"]).startswith("YOUR_"):
        raise ValueError("Firebase project_id is missing or still a placeholder")
    clients = config.get("client", [])
    if not isinstance(clients, list):
        raise ValueError("Firebase client list is invalid")
    matches = [c for c in clients if isinstance(c, dict) and c.get("client_info", {}).get("android_client_info", {}).get("package_name") == "net.arcuata.qiqigram.beta"]
    if len(matches) != 1:
        raise ValueError("Firebase config must contain one net.arcuata.qiqigram.beta client")
    keys = matches[0].get("api_key", [])
    if not any(isinstance(k, dict) and re.fullmatch(r"AIza[0-9A-Za-z_-]{35}", str(k.get("current_key", ""))) for k in keys):
        raise ValueError("Firebase client API key is missing or still a placeholder")
    if not matches[0].get("client_info", {}).get("mobilesdk_app_id") or str(matches[0]["client_info"]["mobilesdk_app_id"]).startswith("YOUR_"):
        raise ValueError("Firebase Android app ID is missing or still a placeholder")
    # Reuse identical input without changing its timestamp (Gradle incremental builds).
    if not destination.is_file() or destination.read_bytes() != data:
        temporary = destination.with_name(destination.name + ".tmp")
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as output:
            output.write(data)
        temporary.replace(destination)
    destination.chmod(0o600)
    print("Local Firebase client configuration prepared for QiQiGram beta")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, AttributeError, TypeError) as error:
        print("Firebase configuration error: " + str(error), file=sys.stderr)
        sys.exit(1)
