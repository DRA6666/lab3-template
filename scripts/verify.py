"""Run the supplied Postman scenario and additional failure checks against a test stack.

Requires Python 3 and Docker; run from the repository root. Uses its own Postman
environment and never modifies the supplied fixtures. It stops/starts only the
services of the explicitly selected Compose project.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid


parser = argparse.ArgumentParser()
parser.add_argument("--project", required=True)
parser.add_argument("--gateway-port", type=int, default=8080)
parser.add_argument("--library-port", type=int, default=8060)
parser.add_argument("--reservation-port", type=int, default=8070)
parser.add_argument("--rating-port", type=int, default=8050)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
os.chdir(root)
reports = root / ".test-results"
reports.mkdir(exist_ok=True)


def docker(*command):
    subprocess.run(["docker", *command], check=True)


def compose(*command):
    docker("compose", "-p", args.project, *command)


def request(port, path, body=None, username="fault-test", headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(f"http://localhost:{port}{path}", data=data,
                                 headers={"Content-Type": "application/json",
                                          "X-User-Name": username, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        return error.code, json.loads(raw) if raw else None


def until(check, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if check():
                return
        except (OSError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError("Timed out waiting for service/retry recovery")


def health(port):
    until(lambda: request(port, "/manage/health")[0] == 200)


def postman(folder):
    print(f"Running supplied Postman folder: {folder}", flush=True)
    docker("run", "--rm", "--network", f"{args.project}_default",
           "-v", f"{root}:/etc/newman", "postman/newman:6-alpine", "run",
           "v4/postman/collection.json", "--folder", folder, "--delay-request", "100",
           "-e", ".test-results/environment.json", "--export-environment",
           ".test-results/environment.json", "--reporters", "cli,json",
           "--reporter-json-export", f".test-results/{folder}.json")


environment = json.loads((root / "v4/postman/environment.json").read_text(encoding="utf-8"))
for item in environment["values"]:
    if item["key"] == "baseUrl":
        item["value"] = "http://gateway:8080"
    if item["key"] == "username":
        item["value"] = "postman-" + uuid.uuid4().hex[:12]
(reports / "environment.json").write_text(json.dumps(environment), encoding="utf-8")

try:
    for port in [args.gateway_port, args.library_port, args.reservation_port, args.rating_port]:
        health(port)
    postman("success")
    for step in range(1, 5):
        compose("stop" if step % 2 else "start", "rating")
        if step % 2 == 0:
            health(args.rating_port)
        postman(f"step{step}")

    print("Checking Library outage, ordered recovery, penalties and idempotency", flush=True)
    user = "fault-" + uuid.uuid4().hex[:12]
    book = "f7cdc58f-2caf-4b15-9727-f89dcc629b27"
    library = "83575e12-7ce0-48ee-9931-51919ff3c9ee"
    g, l, r = args.gateway_port, args.library_port, args.rating_port
    assert request(r, "/api/v1/rating?delta=49", {}, user)[1]["stars"] == 50
    status, reservation = request(g, "/api/v1/reservations", {
        "bookUid": book, "libraryUid": library, "tillDate": "2026-10-10"}, user)
    assert status == 200, (status, reservation)
    uid = reservation["reservationUid"]
    compose("stop", "library")
    for _ in range(4):
        assert request(g, "/api/v1/libraries?city=test")[0] == 500
    status, rows = request(g, "/api/v1/reservations", username=user)
    assert status == 200
    assert rows[0]["book"] == {"bookUid": book}
    assert rows[0]["library"] == {"libraryUid": library}
    return_path = f"/api/v1/reservations/{uid}/return"
    return_body = {"condition": "BAD", "date": "2026-10-11"}
    assert request(g, return_path, return_body, user)[0] == 204
    assert request(r, "/api/v1/rating", username=user)[1]["stars"] == 50
    compose("start", "library")
    health(l)
    until(lambda: request(r, "/api/v1/rating", username=user)[1]["stars"] == 30)
    books_path = f"/api/v1/libraries/{library}/books?showAll=true"
    assert request(l, books_path)[1]["items"][0]["availableCount"] == 1
    assert request(l, books_path)[1]["items"][0]["condition"] == "BAD"
    assert request(g, return_path, return_body, user)[0] == 204
    key = {"Idempotency-Key": uid + ":return"}
    assert request(l, f"/api/v1/libraries/{library}/books/{book}/return?condition=BAD", {}, user, key)[0] == 200
    assert request(r, "/api/v1/rating?delta=-20", {}, user, key)[1]["stars"] == 30
    assert request(l, books_path)[1]["items"][0]["availableCount"] == 1
    compose("restart", "library", "rating")
    health(l)
    health(r)
    assert request(l, f"/api/v1/libraries/{library}/books/{book}/return?condition=BAD", {}, user, key)[0] == 200
    assert request(r, "/api/v1/rating?delta=-20", {}, user, key)[1]["stars"] == 30
    assert request(l, books_path)[1]["items"][0]["availableCount"] == 1
    compose("stop", "reservation")
    assert request(g, "/api/v1/reservations", username=user)[0] == 500
    compose("start", "reservation")
    health(args.reservation_port)
    until(lambda: request(g, "/api/v1/reservations", username=user)[0] == 200)
    print("PASS: all Postman folders and additional failure checks", flush=True)
finally:
    compose("start", "library", "rating", "reservation")
