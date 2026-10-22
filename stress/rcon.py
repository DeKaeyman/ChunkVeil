"""Minimal Minecraft RCON client (stdlib only)."""
import socket
import struct
import threading

LOGIN = 3
COMMAND = 2


class RconError(Exception):
    pass


class Rcon:
    def __init__(self, host, port, password, timeout=10.0):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self.sock = None
        self.request_id = 0
        self.lock = threading.Lock()

    def connect(self):
        self.sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self.sock.settimeout(self.timeout)
        response_id, _ = self._exchange(LOGIN, self.password)
        if response_id == -1:
            raise RconError("RCON authentication failed")
        return self

    def close(self):
        if self.sock is not None:
            try:
                self.sock.close()
            finally:
                self.sock = None

    def command(self, text):
        with self.lock:
            if self.sock is None:
                self.connect()
            _, payload = self._exchange(COMMAND, text)
            return payload

    def _exchange(self, kind, payload):
        self.request_id += 1
        request_id = self.request_id
        body = struct.pack("<ii", request_id, kind) + payload.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(body)) + body)
        response_id, text = self._read_packet()
        return response_id, text

    def _read_exact(self, count):
        data = b""
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise RconError("RCON connection closed")
            data += chunk
        return data

    def _read_packet(self):
        (length,) = struct.unpack("<i", self._read_exact(4))
        data = self._read_exact(length)
        response_id, _kind = struct.unpack("<ii", data[:8])
        text = data[8:-2].decode("utf-8", errors="replace")
        return response_id, text

    def __enter__(self):
        return self.connect()

    def __exit__(self, *exc):
        self.close()
