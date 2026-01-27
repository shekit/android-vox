#!/usr/bin/env python3
"""
Vox Client SDK - Python client for controlling Android via Vox remote control server.

Usage:
    from vox_client import VoxClient

    client = VoxClient("192.168.1.100", port=8080, auth_token="your-token")
    client.connect()

    # Execute a high-level task (uses AI)
    result = client.execute_task("Take a photo")

    # Execute a specific action
    result = client.execute_action("tap Camera")

    # Get device state
    state = client.get_state(include_screenshot=True)

    client.disconnect()
"""

import asyncio
import json
import uuid
from dataclasses import dataclass
from typing import Optional, Callable, Dict, Any, AsyncIterator
import websockets
from websockets.client import WebSocketClientProtocol


@dataclass
class ActionResult:
    success: bool
    action: str
    message: str


@dataclass
class ExecutionResult:
    success: bool
    steps: int
    message: str
    error: Optional[str] = None


@dataclass
class DeviceState:
    ui_tree: dict
    screenshot: Optional[str]  # base64
    foreground_package: str
    timestamp: int


@dataclass
class ExecutionProgress:
    step: int
    action: str
    status: str


class VoxClient:
    """
    Client for connecting to Vox remote control server.
    """

    def __init__(
        self,
        host: str,
        port: int = 8080,
        auth_token: Optional[str] = None
    ):
        self.host = host
        self.port = port
        self.auth_token = auth_token
        self.ws: Optional[WebSocketClientProtocol] = None
        self._pending_responses: Dict[str, asyncio.Future] = {}
        self._event_handlers: Dict[str, Callable] = {}
        self._receive_task: Optional[asyncio.Task] = None

    @property
    def uri(self) -> str:
        return f"ws://{self.host}:{self.port}/control"

    @property
    def is_connected(self) -> bool:
        if self.ws is None:
            return False
        try:
            # websockets 16.x uses state property
            from websockets import State
            return self.ws.state == State.OPEN
        except (ImportError, AttributeError):
            # Fallback for older versions
            return getattr(self.ws, 'open', False)

    async def connect(self):
        """Connect to the server."""
        self.ws = await websockets.connect(self.uri)

        # Authenticate if required
        if self.auth_token:
            await self.ws.send(json.dumps({"token": self.auth_token}))

        # Start receive loop
        self._receive_task = asyncio.create_task(self._receive_loop())

    async def disconnect(self):
        """Disconnect from the server."""
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass

        if self.ws:
            await self.ws.close()
            self.ws = None

    async def _receive_loop(self):
        """Background task to receive messages."""
        try:
            async for message in self.ws:
                data = json.loads(message)

                # Check if it's a response to a pending request
                if "id" in data and data["id"] in self._pending_responses:
                    future = self._pending_responses.pop(data["id"])
                    future.set_result(data)

                # Check if it's an event
                elif data.get("event"):
                    event_type = data.get("type")
                    if event_type in self._event_handlers:
                        await self._event_handlers[event_type](data)

        except websockets.ConnectionClosed:
            pass
        except asyncio.CancelledError:
            pass

    async def _send_and_wait(self, command: dict, timeout: float = 60.0) -> dict:
        """Send a command and wait for response."""
        if not self.is_connected:
            raise ConnectionError("Not connected to server")

        request_id = command["id"]
        future = asyncio.get_event_loop().create_future()
        self._pending_responses[request_id] = future

        await self.ws.send(json.dumps(command))

        try:
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            self._pending_responses.pop(request_id, None)
            raise TimeoutError(f"Request {request_id} timed out")

    async def ping(self) -> bool:
        """Check if server is responding."""
        response = await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "ping"
        }, timeout=5.0)
        return response.get("type") == "pong"

    async def get_state(self, include_screenshot: bool = False) -> DeviceState:
        """Get current device state."""
        response = await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "get_state",
            "include_screenshot": include_screenshot
        })

        return DeviceState(
            ui_tree=json.loads(response.get("ui_tree", "{}")),
            screenshot=response.get("screenshot"),
            foreground_package=response.get("foreground_package", ""),
            timestamp=response.get("timestamp", 0)
        )

    async def get_screenshot(self) -> Optional[str]:
        """Get screenshot as base64."""
        response = await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "get_screenshot"
        })
        return response.get("data")

    async def get_ui_tree(self) -> dict:
        """Get UI tree."""
        response = await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "get_ui_tree"
        })
        return json.loads(response.get("tree", "{}"))

    async def execute_action(self, action: str) -> ActionResult:
        """Execute a specific action (e.g., 'tap Settings', 'type hello into Search')."""
        response = await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "execute_action",
            "action": action
        })

        return ActionResult(
            success=response.get("success", False),
            action=response.get("action", action),
            message=response.get("message", "")
        )

    async def execute_task(
        self,
        task: str,
        progress_callback: Optional[Callable[[ExecutionProgress], None]] = None,
        timeout: float = 300.0
    ) -> ExecutionResult:
        """
        Execute a high-level task using AI.

        Args:
            task: The task to execute (e.g., "Take a photo", "Open Chrome and search for cats")
            progress_callback: Optional callback for progress updates
            timeout: Maximum time to wait for completion

        Returns:
            ExecutionResult with success status and details
        """
        request_id = str(uuid.uuid4())

        # Set up response handling
        future = asyncio.get_event_loop().create_future()
        self._pending_responses[request_id] = future

        # Send the request
        await self.ws.send(json.dumps({
            "id": request_id,
            "type": "execute_task",
            "task": task
        }))

        # Wait for completion, handling progress updates
        start_time = asyncio.get_event_loop().time()
        result = None

        while True:
            remaining = timeout - (asyncio.get_event_loop().time() - start_time)
            if remaining <= 0:
                raise TimeoutError(f"Task '{task}' timed out")

            try:
                response = await asyncio.wait_for(future, timeout=min(remaining, 5.0))

                response_type = response.get("type")

                if response_type == "execution_progress":
                    if progress_callback:
                        progress_callback(ExecutionProgress(
                            step=response.get("step", 0),
                            action=response.get("action", ""),
                            status=response.get("status", "")
                        ))
                    # Reset future for next message
                    future = asyncio.get_event_loop().create_future()
                    self._pending_responses[request_id] = future

                elif response_type == "execution_completed":
                    return ExecutionResult(
                        success=True,
                        steps=response.get("steps", 0),
                        message=response.get("message", "Completed")
                    )

                elif response_type == "execution_failed":
                    return ExecutionResult(
                        success=False,
                        steps=response.get("steps", 0),
                        message=response.get("message", "Failed"),
                        error=response.get("error")
                    )

                elif response_type == "error":
                    return ExecutionResult(
                        success=False,
                        steps=0,
                        message="Error",
                        error=response.get("error")
                    )

            except asyncio.TimeoutError:
                # No response yet, continue waiting
                future = asyncio.get_event_loop().create_future()
                self._pending_responses[request_id] = future
                continue

    async def cancel(self):
        """Cancel current execution."""
        await self._send_and_wait({
            "id": str(uuid.uuid4()),
            "type": "cancel"
        }, timeout=5.0)

    def on_ui_changed(self, callback: Callable[[dict], None]):
        """Register a callback for UI change events."""
        self._event_handlers["ui_changed"] = callback

    def on_state_changed(self, callback: Callable[[dict], None]):
        """Register a callback for execution state change events."""
        self._event_handlers["state_changed"] = callback


# Synchronous wrapper for convenience
class VoxClientSync:
    """
    Synchronous wrapper around VoxClient.
    Creates its own event loop for simple usage.
    """

    def __init__(self, host: str, port: int = 8080, auth_token: Optional[str] = None):
        self._client = VoxClient(host, port, auth_token)
        self._loop = asyncio.new_event_loop()

    def connect(self):
        self._loop.run_until_complete(self._client.connect())

    def disconnect(self):
        self._loop.run_until_complete(self._client.disconnect())
        self._loop.close()

    def ping(self) -> bool:
        return self._loop.run_until_complete(self._client.ping())

    def get_state(self, include_screenshot: bool = False) -> DeviceState:
        return self._loop.run_until_complete(self._client.get_state(include_screenshot))

    def get_screenshot(self) -> Optional[str]:
        return self._loop.run_until_complete(self._client.get_screenshot())

    def get_ui_tree(self) -> dict:
        return self._loop.run_until_complete(self._client.get_ui_tree())

    def execute_action(self, action: str) -> ActionResult:
        return self._loop.run_until_complete(self._client.execute_action(action))

    def execute_task(self, task: str, progress_callback: Optional[Callable] = None) -> ExecutionResult:
        return self._loop.run_until_complete(self._client.execute_task(task, progress_callback))

    def cancel(self):
        self._loop.run_until_complete(self._client.cancel())

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.disconnect()


if __name__ == "__main__":
    # Example usage
    import sys

    if len(sys.argv) < 2:
        print("Usage: python vox_client.py <host> [port] [command]")
        print("Example: python vox_client.py 192.168.1.100 8080 'Take a photo'")
        sys.exit(1)

    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
    task = sys.argv[3] if len(sys.argv) > 3 else None

    async def main():
        client = VoxClient(host, port)
        await client.connect()

        print(f"Connected to {host}:{port}")

        if await client.ping():
            print("Server is responding")

        if task:
            print(f"Executing task: {task}")

            def on_progress(p: ExecutionProgress):
                print(f"  Step {p.step}: {p.action} ({p.status})")

            result = await client.execute_task(task, progress_callback=on_progress)
            print(f"Result: {'Success' if result.success else 'Failed'}")
            print(f"  Steps: {result.steps}")
            print(f"  Message: {result.message}")
            if result.error:
                print(f"  Error: {result.error}")
        else:
            # Just get state
            state = await client.get_state()
            print(f"Foreground: {state.foreground_package}")
            print(f"UI Tree nodes: {len(str(state.ui_tree))} chars")

        await client.disconnect()

    asyncio.run(main())
