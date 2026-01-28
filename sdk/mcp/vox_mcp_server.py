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
from typing import Optional, List, Union

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

CRITICAL WORKFLOW - Follow this for every task:
1. Connect to the phone using phone_connect()
2. ALWAYS call phone_get_state() before taking any action
   - This returns the ui_tree (structured data) with all actionable/labeled elements
   - The ui_tree contains exact text, content descriptions, and resource IDs for all elements
3. Analyze the ui_tree to find your target element - use its EXACT text or resource ID
4. Execute the appropriate action using phone_execute() with identifiers from the ui_tree
5. Call phone_get_state() again to verify the result and see the new screen
6. Call phone_get_screenshot() to visually confirm the action worked
7. Repeat steps 3-6 until the task is complete

KEY PRINCIPLE: The ui_tree is your source of truth. Every action that targets an element
(tap, type, long_press) MUST use text or IDs found in the ui_tree. Never guess element names
based on what you think a button might say - always verify in the tree first.

Example:
- DON'T guess: "tap Send" (might be wrong)
- DO: Look at ui_tree, find the actual text is "SMS", use "tap SMS"

LAUNCHING APPS:
- Before launching an app, call phone_get_apps() to get the list of installed apps
- The launch command ONLY accepts exact package IDs (e.g., "com.android.chrome")
- DON'T guess package names - look them up first

Available actions for phone_execute:
- tap <text>: Tap on element containing text (e.g., "tap Settings"). Prefers buttons/list items over input fields when multiple elements match.
- tap_id <id>: Tap on element with resource ID (e.g., "tap_id com.android.settings:id/search_action_bar")
- long_press <text>: Long press on element containing text (e.g., "long_press Photo.jpg")
- tap_at <x> <y>: Tap at specific screen coordinates (e.g., "tap_at 540 1200")
- swipe_left: Swipe left (e.g., for carousel navigation)
- swipe_right: Swipe right
- swipe_up: Swipe up (e.g., to dismiss)
- swipe_down: Swipe down (e.g., to pull down notifications)
- type <text>: Type text into the focused field, REPLACING existing content (e.g., "type hello world")
- type <text> into <field>: Type into specific field, REPLACING existing content (e.g., "type john into Username")
- append <text>: Append text to the focused field, keeping existing content (e.g., "append more text")
- append <text> into <field>: Append text to specific field (e.g., "append @gmail.com into Email")
- launch <package>: Launch app by exact package ID (e.g., "launch com.google.android.GoogleCamera")
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
async def phone_get_apps() -> str:
    """
    Get list of installed apps on the phone.

    Use this to find the correct package name before launching an app.
    Returns a list of apps with their display names and package IDs.

    Returns:
        JSON array of {name, package} objects, sorted alphabetically by name
    """
    try:
        client = await get_client()
        apps = await client.get_apps()
        return json.dumps(apps, indent=2)
    except Exception as e:
        raise RuntimeError(f"Failed to get apps: {str(e)}")


@mcp.tool()
async def phone_execute(action: str) -> str:
    """
    Execute an action on the phone.

    Args:
        action: The action to execute. Supported actions:
            - "tap <text>": Tap on element containing text (prefers buttons over input fields)
            - "tap_id <id>": Tap on element with resource ID
            - "long_press <text>": Long press on element (e.g., "long_press Photo.jpg")
            - "tap_at <x> <y>": Tap at screen coordinates (e.g., "tap_at 540 1200")
            - "swipe_left": Swipe left (carousel, dismiss)
            - "swipe_right": Swipe right
            - "swipe_up": Swipe up (dismiss, pull up)
            - "swipe_down": Swipe down (notifications)
            - "type <text>": Type into focused field, REPLACING content
            - "type <text> into <field>": Type into specific field, REPLACING content
            - "append <text>": Append to focused field, KEEPING existing content
            - "append <text> into <field>": Append to specific field
            - "launch <package>": Launch app by exact package ID
            - "back": Press back button
            - "home": Press home button
            - "enter": Press enter/submit button
            - "scroll_up": Scroll up
            - "scroll_down": Scroll down

    Returns:
        Result message indicating success or failure. On failure, also includes
        the current UI state to help identify the correct element.
    """
    try:
        client = await get_client()
        result = await client.execute_action(action)

        if result.success:
            return f"Success: {result.message}"
        else:
            # On failure, fetch current UI state to help with retry
            try:
                state = await client.get_state(include_screenshot=False, compact=True)
                ui_state = {
                    "foreground_package": state.foreground_package,
                    "ui_tree": state.ui_tree,
                    "timestamp": state.timestamp
                }
                return json.dumps({
                    "result": "failed",
                    "message": result.message,
                    "hint": "Action failed. Here is the current UI state to help you find the correct element:",
                    "current_state": ui_state
                }, indent=2)
            except Exception:
                # If we can't get state, just return the failure message
                return f"Failed: {result.message}"
    except Exception as e:
        raise RuntimeError(f"Failed to execute action '{action}': {str(e)}")


@mcp.tool()
async def phone_get_state() -> str:
    """
    Get the current state of the phone including UI tree and foreground app.

    This is the PRIMARY tool for understanding what's on screen. Always call this
    before executing actions to know what elements are available.

    Returns a JSON object with:
    - foreground_package: The package name of the current app
    - ui_tree: A compact array of actionable/labeled UI elements, each with:
        - text: Visible text (if any)
        - desc: Content description (if any)
        - id: Resource ID (shortened, if any)
        - click/longClick/edit/scroll/check: Action flags (only present if true)
        - bounds: [left, top, right, bottom] coordinates
    - timestamp: When the state was captured

    Use element text, desc, or id for tap/type actions. Use phone_get_screenshot()
    separately if you need to visually see the screen.
    """
    try:
        client = await get_client()
        state = await client.get_state(include_screenshot=False, compact=True)

        result = {
            "foreground_package": state.foreground_package,
            "ui_tree": state.ui_tree,
            "timestamp": state.timestamp
        }

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
