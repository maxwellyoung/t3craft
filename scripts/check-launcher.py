#!/usr/bin/env python3
"""Exercise the launcher lifecycle with disposable console servers, no Minecraft or user worlds."""
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time


def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def listening(number):
    try:
        with socket.create_connection(('127.0.0.1', number), timeout=.2):
            return True
    except OSError:
        return False


def wait_for(check):
    for _ in range(100):
        if check():
            return
        time.sleep(.1)
    raise RuntimeError('launcher fixture timed out')


with tempfile.TemporaryDirectory(prefix='t3craft-launcher-check-') as folder:
    root = Path(folder)
    (root / 'scripts').mkdir()
    shutil.copy2(Path(__file__).with_name('launch-macos.sh'), root / 'scripts/launch-macos.sh')
    (root / 'bin').mkdir()
    for name, body in [('osascript', 'exit 0'), ('pgrep', 'exit 1')]:
        p = root / 'bin' / name
        p.write_text('#!/bin/sh\n' + body + '\n')
        p.chmod(0o755)
    (root / 'jdk/bin').mkdir(parents=True)
    (root / 'jdk/bin/java').write_text('#!/bin/sh\nexit 0\n')
    (root / 'jdk/bin/java').chmod(0o755)
    server = root / 'console.py'
    server.write_text('''import socket,sys
from pathlib import Path
with socket.socket() as listener:
    listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    listener.bind(('127.0.0.1',int(sys.argv[1])))
    listener.listen()
    for line in sys.stdin:
        if line.strip() == 'stop':
            Path(sys.argv[2]).write_text('saved and stopped')
            break
''')
    wrapper = root / 'gradlew'
    wrapper.write_text('''#!/usr/bin/env python3
import os,sys,time
from pathlib import Path
args=sys.argv[1:]
if args[0] == 'runClient':
    time.sleep(.25)
    sys.exit(int(os.environ.get('FIXTURE_CLIENT_EXIT','0')))
world=Path(next(x.split('=',1)[1] for x in args if x.startswith('-PserverDir=')))
number=next(x.split('=',1)[1] for x in (world/'server.properties').read_text().splitlines() if x.startswith('server-port='))
marker=next(x.split('=',1)[1] for x in args if x.startswith('-PlauncherId='))
(world/'started-pid').write_text(str(os.getpid()))
os.execv(sys.executable,[sys.executable,str(Path(__file__).with_name('console.py')),number,str(world/'stopped'), 'java', '-Ddli.env=server', '-Dt3craft.launcherId='+marker])
''')
    wrapper.chmod(0o755)
    env = {**os.environ, 'PATH': str(root / 'bin') + ':' + os.environ['PATH'], 'JAVA_HOME': str(root / 'jdk')}
    peers = []
    try:
        # A second console server intentionally matches the old broad pkill expression.
        peer_port = port()
        peer = subprocess.Popen(['python3', str(server), str(peer_port), str(root / 'peer-stopped'), 'java', '-Ddli.env=server'], stdin=subprocess.PIPE)
        peers.append(peer)
        wait_for(lambda: listening(peer_port))
        for exit_code in [0, 7]:
            world = root / ('world-' + str(exit_code)); world.mkdir()
            number = port(); (world / 'server.properties').write_text('server-port=' + str(number) + '\n')
            profile = root / 'scripts/check.local'
            profile.write_text('T3CRAFT_SERVER_DIR="' + str(world) + '"\nT3CRAFT_LOG_DIR="' + str(root / 'logs') + '"\n')
            result = subprocess.run(['bash', str(root / 'scripts/launch-macos.sh')], env={**env, 'T3CRAFT_PROFILE': str(profile), 'FIXTURE_CLIENT_EXIT': str(exit_code)}, capture_output=True, text=True, timeout=20)
            assert result.returncode == exit_code, result.stderr
            assert (world / 'stopped').read_text() == 'saved and stopped', 'owned server did not receive stop'
            assert not listening(number), 'owned server remained listening'
            assert peer.poll() is None and listening(peer_port), 'unrelated server was stopped'
        profile.write_text('T3CRAFT_SERVER_DIR="' + str(root / 'reuse') + '"\nT3CRAFT_LOG_DIR="' + str(root / 'logs') + '"\n')
        (root / 'reuse').mkdir(); (root / 'reuse/server.properties').write_text('server-port=' + str(peer_port) + '\n')
        result = subprocess.run(['bash', str(root / 'scripts/launch-macos.sh')], env={**env, 'T3CRAFT_PROFILE': str(profile)}, capture_output=True, text=True, timeout=10)
        assert result.returncode == 0, result.stderr
        assert peer.poll() is None and listening(peer_port), 'reused server was stopped'
        print('PASS launcher owns only its new server, saves on client success/failure, and preserves reused/unrelated servers')
    finally:
        for receipt in root.glob('world-*/started-pid'):
            pid = int(receipt.read_text())
            command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
            if str(root / 'console.py') in command:
                try: os.kill(pid, 15)
                except ProcessLookupError: pass
        for process in peers:
            if process.poll() is None:
                process.stdin.write(b'stop\n'); process.stdin.flush()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=5)
