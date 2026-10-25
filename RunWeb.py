"""Start the web app (dance/editor/server.py) and open it in the browser.

    python RunWeb.py            # http://127.0.0.1:8771
    python RunWeb.py --port 9000
"""
import os, subprocess, sys, threading, webbrowser

SERVER = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'dance', 'editor', 'server.py')

port = '8771'
if '--port' in sys.argv[1:-1]:
    port = sys.argv[sys.argv.index('--port') + 1]

# open the page once the server has had a moment to start
threading.Timer(1.5, webbrowser.open, [f'http://127.0.0.1:{port}/']).start()

try:
    sys.exit(subprocess.call([sys.executable, SERVER] + sys.argv[1:]))
except KeyboardInterrupt:
    pass
