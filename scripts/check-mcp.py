#!/usr/bin/env python3
"""Read-only HTTP boundary check while the isolated Minecraft test client is running.

Only requests tools/list and ping; it never runs a game tool or changes a world.
"""
import argparse
import http.client
import json
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--wait', type=int, default=0, help='Seconds to wait for the test client, up to 60')
args = parser.parse_args()
if not 0 <= args.wait <= 60:
    parser.error('--wait must be between 0 and 60')

payload = json.dumps({'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).encode()


def request(headers=None, body=payload):
    connection = http.client.HTTPConnection('127.0.0.1', 25590, timeout=3)
    try:
        connection.request('POST', '/mcp', body, headers or {'Content-Type': 'application/json'})
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


deadline = time.monotonic() + args.wait
while True:
    try:
        code, body = request()
        assert code == 200, 'native client refused'
        names = {tool['name'] for tool in json.loads(body)['result']['tools']}
        assert names == {'minecraft_status', 'minecraft_nearby_blocks', 'minecraft_say', 'minecraft_run_command'}, 'not the expected Minecraft endpoint'
        break
    except OSError:
        if time.monotonic() >= deadline:
            raise SystemExit('Start the isolated Minecraft test client first.')
        time.sleep(.1)

for origin in ['http://localhost.attacker.example', 'http://127.0.0.1.attacker.example', 'http://localhost:3000', 'null', '']:
    assert request({'Origin': origin})[0] == 403, 'browser origin accepted'
for host in ['attacker.example:25590', 'localhost.attacker.example:25590', '127.0.0.1.attacker.example:25590', 'localhost:25591', 'user@localhost:25590']:
    assert request({'Host': host})[0] == 403, 'noncanonical Host accepted'
for body in [b'{bad', b'null', b'{"id":1}']:
    assert request(body=body)[0] == 400, 'malformed message accepted'
assert request(body=b'x' * 65537)[0] == 413, 'oversized body accepted'
assert request(body=payload + b' ' * (65536 - len(payload)))[0] == 200, 'valid maximum body refused'
assert request({'Host': 'localhost:25590', 'Content-Type': 'application/json'})[0] == 200, 'native localhost refused'
print('PASS actual Minecraft MCP: native tools/list works, browser origins and noncanonical Hosts refused, malformed/oversized requests rejected, endpoint remains healthy')
