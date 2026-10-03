"""Print the Gradle properties for Firebase push, read from google-services.json.

Usage: GOOGLE_SERVICES_JSON='<file contents>' python scripts/firebase_config.py <package id>
Prints one -P<name>=<value> argument per line. Prints nothing (with a warning) when the
JSON is not set, so the app still builds, just without push.
"""
import json
import os
import sys


def main():
    package = sys.argv[1]
    raw = os.environ.get("GOOGLE_SERVICES_JSON", "").strip()
    if not raw:
        print("No GOOGLE_SERVICES_JSON secret - building without push. See README.",
              file=sys.stderr)
        return

    try:
        config = json.loads(raw)
    except json.JSONDecodeError as e:
        sys.exit(f"GOOGLE_SERVICES_JSON is not valid JSON: {e}")

    clients = config.get("client", [])
    client = next((c for c in clients
                   if c.get("client_info", {}).get("android_client_info", {})
                       .get("package_name") == package), None)
    if client is None:
        found = [c.get("client_info", {}).get("android_client_info", {}).get("package_name")
                 for c in clients]
        sys.exit(f"google-services.json has no Android app '{package}' (it has: {found}). "
                 "Add the app in the Firebase console and download the file again.")

    project = config.get("project_info", {})
    keys = client.get("api_key") or [{}]
    values = {
        "firebaseAppId": client["client_info"].get("mobilesdk_app_id", ""),
        "firebaseApiKey": keys[0].get("current_key", ""),
        "firebaseSenderId": project.get("project_number", ""),
        "firebaseProjectId": project.get("project_id", ""),
    }
    missing = [name for name, value in values.items() if not value]
    if missing:
        sys.exit(f"google-services.json is missing: {', '.join(missing)}")
    for name, value in values.items():
        print(f"-P{name}={value}")


if __name__ == "__main__":
    main()
