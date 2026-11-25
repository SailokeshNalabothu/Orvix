#!/usr/bin/env python3
"""
Orvix Developer Platform Dashboard Standalone Server
Launches a lightweight local HTTP server to preview the Orvix commercial dashboard.
Usage:
    python dashboard/serve.py [port]
"""

import http.server
import socketserver
import os
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 3000
DASHBOARD_DIR = os.path.dirname(os.path.abspath(__file__))

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=DASHBOARD_DIR, **kwargs)

if __name__ == "__main__":
    with socketserver.TCPServer(("", PORT), Handler) as httpd:
        print(f"==================================================================")
        print(f"  Orvix Developer Platform Dashboard")
        print(f"  Live UI Preview: http://localhost:{PORT}")
        print(f"  Gateway Backend: http://localhost:8080/dashboard")
        print(f"==================================================================")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down server.")
