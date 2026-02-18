# Vox MCP Server

MCP server that exposes Android phone control as tools for Claude Code.

## Overview

This server allows Claude Code to control an Android phone via the Vox accessibility service. The phone runs a WebSocket server, and this MCP server bridges Claude Code's tool calls to that connection.

**Flow**: User → Claude Code → MCP Server → WebSocket → Phone → Action

## Prerequisites

1. **Phone Setup**:
   - Install the Vox app on your Android phone
   - Enable the Vox accessibility service in Settings → Accessibility
   - Start the Remote Control service from the Vox app
   - Note the phone's IP address and port (default: 8080)

2. **Python 3.10+** on your computer

## Installation

```bash
cd sdk/mcp
python -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate
pip install -r requirements.txt
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VOX_PHONE_HOST` | `localhost` | Phone's IP address |
| `VOX_PHONE_PORT` | `8080` | WebSocket port |
| `VOX_AUTH_TOKEN` | (none) | Authentication token |

### Claude Code Setup

Add the MCP server to your Claude Code configuration.

#### Option 1: VS Code Extension

Add to your VS Code `settings.json`:

```json
{
  "claude.mcpServers": {
    "vox-phone": {
      "command": "/path/to/android-vox/sdk/mcp/venv/bin/python",
      "args": ["/path/to/android-vox/sdk/mcp/vox_mcp_server.py"],
      "env": {
        "VOX_PHONE_HOST": "192.168.1.100",
        "VOX_PHONE_PORT": "8080"
      }
    }
  }
}
```

#### Option 2: Claude Desktop App

Add to `~/.config/claude/claude_desktop_config.json` (Linux/Mac) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "vox-phone": {
      "command": "/path/to/android-vox/sdk/mcp/venv/bin/python",
      "args": ["/path/to/android-vox/sdk/mcp/vox_mcp_server.py"],
      "env": {
        "VOX_PHONE_HOST": "192.168.1.100",
        "VOX_PHONE_PORT": "8080"
      }
    }
  }
}
```

Replace `/path/to/android-vox` with your actual path and `192.168.1.100` with your phone's IP.

## Available Tools

### phone_connect

Connect to the phone's WebSocket server.

```
phone_connect(host?, port?, auth_token?) → connection status
```

Parameters override environment variables if provided.

### phone_get_screenshot

Get a screenshot of the phone's current screen.

```
phone_get_screenshot() → image
```

Returns an image that Claude can analyze visually.

### phone_get_ui_tree

Get the UI accessibility tree.

```
phone_get_ui_tree() → JSON string
```

Returns a JSON structure describing all visible UI elements with their text, bounds, and interaction states.

### phone_execute

Execute an action on the phone.

```
phone_execute(action) → success/failure message
```

Supported actions:
- `tap <text>` - Tap element containing text (e.g., "tap Settings")
- `tap_id <id>` - Tap element with resource ID
- `type <text>` - Type into focused field
- `type <text> into <field>` - Type into specific field
- `launch <app>` - Launch app by name or package
- `back` - Press back button
- `home` - Press home button
- `enter` - Press enter/submit
- `scroll_up` / `scroll_down` - Scroll the current view

### phone_get_state

Get full device state in one call.

```
phone_get_state(include_screenshot?) → JSON string
```

Returns:
- `foreground_package`: Currently active app
- `ui_tree`: Full accessibility tree
- `screenshot`: Base64 image (if requested)
- `timestamp`: Server timestamp

### phone_disconnect

Disconnect from the phone.

```
phone_disconnect() → status message
```

## Usage Examples

Once configured, you can ask Claude Code:

- "Connect to my phone at 192.168.1.100"
- "Take a screenshot of my phone"
- "Open the Camera app on my phone"
- "Take a photo on my phone"
- "Open Settings and turn on Wi-Fi"

Claude will use the MCP tools to control your phone autonomously.

## USB Connection via ADB Port Forwarding

If your phone is connected via USB and you can't reach it over the network, use ADB port forwarding:

```bash
# Forward local port 8080 to phone's port 8080
adb forward tcp:8080 tcp:8080

# Now use localhost in your configuration
VOX_PHONE_HOST=localhost
```

This allows the MCP server to connect to the phone through the USB connection.

## Troubleshooting

### Connection refused
- Ensure the Vox Remote Control service is running on the phone (tap "Enable Remote Control" in the app)
- Check that the phone and computer are on the same network, OR use ADB port forwarding
- Verify the IP address and port are correct

### Authentication failed
- If you set an auth token on the phone, ensure `VOX_AUTH_TOKEN` is set correctly

### Tool not found
- Restart Claude Code after adding the MCP configuration
- Check that the Python path in the config is correct

## Security Notes

- The WebSocket connection is unencrypted by default
- Use on trusted networks only
- The phone exposes full accessibility control - anyone with access can control the phone
- Consider using authentication tokens in production
