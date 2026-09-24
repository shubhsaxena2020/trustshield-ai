"""Dedicated subprocess entry point; stdout is one JSON telemetry result only."""
from services.analysis.web_sandbox.browser_worker import main

if __name__ == "__main__":
    raise SystemExit(main())
