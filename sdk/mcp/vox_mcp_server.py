#!/usr/bin/env python3
"""
Vox MCP Server - Expose Android phone control as MCP tools for Claude Code.

This server allows Claude Code to control an Android phone via the Vox accessibility service.
The phone runs a WebSocket server (RemoteControlService), and this MCP server bridges
Claude Code's tool calls to that WebSocket connection.

Usage:
    1. Start the Vox remote control service on your phone
    2. Configure this MCP server in Claude Code's settings
    3. Ask Claude Code to control your phone

Environment Variables:
    VOX_PHONE_HOST: Phone's IP address (default: localhost)
    VOX_PHONE_PORT: WebSocket port (default: 8080)
    VOX_AUTH_TOKEN: Authentication token (optional)
"""

import asyncio
import base64
import json
import os
import sys
from typing import Optional

# Add parent directory to path to import vox_client
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'python'))

from mcp.server.fastmcp import FastMCP, Image

# Import VoxClient from sibling directory
from vox_client import VoxClient, ActionResult, DeviceState


# Configuration from environment
PHONE_HOST = os.environ.get("VOX_PHONE_HOST", "localhost")
PHONE_PORT = int(os.environ.get("VOX_PHONE_PORT", "8080"))
AUTH_TOKEN = os.environ.get("VOX_AUTH_TOKEN")

# Global client instance for persistent connection
_client: Optional[VoxClient] = None
_client_lock = asyncio.Lock()


# Initialize MCP server
mcp = FastMCP(
    "Vox Phone Control",
    instructions="""
This MCP server provides tools to control an Android phone via the Vox accessibility service.

Typical workflow:
1. Connect to the phone using phone_connect()
2. Get the current screen state with phone_get_state() or phone_get_screenshot()
3. Analyze the UI and execute actions with phone_execute()
4. Repeat steps 2-3 until the task is complete

Available actions for phone_execute:
- tap <text>: Tap on element containing text (e.g., "tap Settings")
- tap_id <id>: Tap on element with resource ID (e.g., "tap_id com.android.settings:id/search_action_bar")
- long_press <text>: Long press on element containing text (e.g., "long_press Photo.jpg")
- tap_at <x> <y>: Tap at specific screen coordinates (e.g., "tap_at 540 1200")
- swipe_left: Swipe left (e.g., for carousel navigation)
- swipe_right: Swipe right
- swipe_up: Swipe up (e.g., to dismiss)
- swipe_down: Swipe down (e.g., to pull down notifications)
- type <text>: Type text into the focused field (e.g., "type hello world")
- type <text> into <field>: Type into specific field (e.g., "type john into Username")
- launch <app>: Launch app by name or package (e.g., "launch Camera" or "launch com.google.android.GoogleCamera")
- back: Press the back button
- home: Press the home button
- enter: Press the enter/submit button
- scroll_up: Scroll up in the current view
- scroll_down: Scroll down in the current view
"""
)


async def get_client() -> VoxClient:
    """Get or create the VoxClient connection."""
    global _client
    async with _client_lock:
        if _client is None or not _client.is_connected:
            _client = VoxClient(PHONE_HOST, PHONE_PORT, AUTH_TOKEN)
            await _client.connect()
        return _client


@mcp.tool()
async def phone_connect(
    host: Optional[str] = None,
    port: Optional[int] = None,
    auth_token: Optional[str] = None
) -> str:
    """
    Connect to the Android phone's Vox remote control server.

    Args:
        host: Phone's IP address (uses VOX_PHONE_HOST env var if not provided)
        port: WebSocket port (uses VOX_PHONE_PORT env var if not provided)
        auth_token: Authentication token (uses VOX_AUTH_TOKEN env var if not provided)

    Returns:
        Connection status message
    """
    global _client, PHONE_HOST, PHONE_PORT, AUTH_TOKEN

    # Update globals if provided
    if host:
        PHONE_HOST = host
    if port:
        PHONE_PORT = port
    if auth_token:
        AUTH_TOKEN = auth_token

    try:
        async with _client_lock:
            # Disconnect existing connection if any
            if _client and _client.is_connected:
                await _client.disconnect()

            # Create new connection
            _client = VoxClient(PHONE_HOST, PHONE_PORT, AUTH_TOKEN)
            await _client.connect()

            # Verify connection with ping
            if await _client.ping():
                return f"Connected to phone at {PHONE_HOST}:{PHONE_PORT}"
            else:
                return f"Connected but ping failed - server may not be responding properly"
    except Exception as e:
        return f"Failed to connect to {PHONE_HOST}:{PHONE_PORT}: {str(e)}"


@mcp.tool()
async def phone_get_screenshot() -> Image:
    """
    Get a screenshot of the phone's current screen.

    Returns:
        Screenshot image that Claude can analyze visually
    """
    try:
        client = await get_client()
        screenshot_b64 = await client.get_screenshot()

        if not screenshot_b64:
            raise ValueError("No screenshot data received from phone")

        # Decode base64 to raw bytes
        image_data = base64.b64decode(screenshot_b64)
        return Image(data=image_data, format="png")
    except Exception as e:
        raise RuntimeError(f"Failed to get screenshot: {str(e)}")


@mcp.tool()
async def phone_get_ui_tree() -> str:
    """
    Get the UI tree of the phone's current screen.

    The UI tree is a JSON structure describing all visible UI elements,
    including their text, content descriptions, resource IDs, bounds,
    and whether they are clickable/focusable/etc.

    Returns:
        JSON string containing the UI tree
    """
    try:
        client = await get_client()
        ui_tree = await client.get_ui_tree()
        return json.dumps(ui_tree, indent=2)
    except Exception as e:
        raise RuntimeError(f"Failed to get UI tree: {str(e)}")


@mcp.tool()
async def phone_execute(action: str) -> str:
    """
    Execute an action on the phone.

    Args:
        action: The action to execute. Supported actions:
            - "tap <text>": Tap on element containing text (e.g., "tap Settings")
            - "tap_id <id>": Tap on element with resource ID
            - "long_press <text>": Long press on element (e.g., "long_press Photo.jpg")
            - "tap_at <x> <y>": Tap at screen coordinates (e.g., "tap_at 540 1200")
            - "swipe_left": Swipe left (carousel, dismiss)
            - "swipe_right": Swipe right
            - "swipe_up": Swipe up (dismiss, pull up)
            - "swipe_down": Swipe down (notifications)
            - "type <text>": Type text into focused field
            - "type <text> into <field>": Type into specific field
            - "launch <app>": Launch app by name or package
            - "back": Press back button
            - "home": Press home button
            - "enter": Press enter/submit button
            - "scroll_up": Scroll up
            - "scroll_down": Scroll down

    Returns:
        Result message indicating success or failure
    """
    try:
        client = await get_client()
        result = await client.execute_action(action)

        if result.success:
            return f"Success: {result.message}"
        else:
            return f"Failed: {result.message}"
    except Exception as e:
        raise RuntimeError(f"Failed to execute action '{action}': {str(e)}")


@mcp.tool()
async def phone_get_state(include_screenshot: bool = True) -> str:
    """
    Get the full state of the phone including UI tree, foreground app, and optionally a screenshot.

    This is a convenience tool that combines multiple pieces of information.
    For tasks requiring visual analysis, use include_screenshot=True.

    Args:
        include_screenshot: Whether to include a screenshot (default: True)

    Returns:
        JSON string containing:
        - foreground_package: The currently active app's package name
        - ui_tree: The UI tree structure
        - screenshot: Base64 encoded screenshot (if requested)
        - timestamp: Server timestamp
    """
    try:
        client = await get_client()
        state = await client.get_state(include_screenshot=include_screenshot)

        result = {
            "foreground_package": state.foreground_package,
            "ui_tree": state.ui_tree,
            "timestamp": state.timestamp
        }

        if include_screenshot and state.screenshot:
            result["screenshot"] = state.screenshot

        return json.dumps(result, indent=2)
    except Exception as e:
        raise RuntimeError(f"Failed to get device state: {str(e)}")


@mcp.tool()
async def phone_disconnect() -> str:
    """
    Disconnect from the phone.

    Returns:
        Disconnection status message
    """
    global _client
    try:
        async with _client_lock:
            if _client and _client.is_connected:
                await _client.disconnect()
                _client = None
                return "Disconnected from phone"
            else:
                return "Not connected to phone"
    except Exception as e:
        return f"Error disconnecting: {str(e)}"


if __name__ == "__main__":
    # Run the MCP server with stdio transport (for Claude Code integration)
    mcp.run()
