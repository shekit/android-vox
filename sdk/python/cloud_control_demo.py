#!/usr/bin/env python3
"""
Cloud Control Demo - Control an Android phone from the cloud using Claude.

This demonstrates the full architecture:
  Cloud (this script) -> WebSocket -> Phone (Vox) -> Accessibility API -> Apps

The AI (Claude) runs in the cloud and sends commands to the phone.
The phone is a "dumb" executor - it just does what Claude tells it to do.

Usage:
    export ANTHROPIC_API_KEY=sk-ant-...
    python cloud_control_demo.py <phone_ip> <task>

Example:
    python cloud_control_demo.py 192.168.1.100 "Take a photo with the camera"
"""

import asyncio
import base64
import json
import os
import sys
from dataclasses import dataclass
from typing import Optional, List

# Requires: pip install anthropic websockets
import anthropic
from vox_client import VoxClient, DeviceState, ActionResult


@dataclass
class ActionRecord:
    step: int
    action: str
    result: str
    ui_changed: bool


class CloudController:
    """
    Controls an Android phone from the cloud using Claude for decision-making.
    """

    def __init__(
        self,
        phone_host: str,
        phone_port: int = 8080,
        auth_token: Optional[str] = None,
        anthropic_api_key: Optional[str] = None,
        model: str = "claude-sonnet-4-20250514"
    ):
        self.client = VoxClient(phone_host, phone_port, auth_token)
        self.anthropic = anthropic.Anthropic(
            api_key=anthropic_api_key or os.environ.get("ANTHROPIC_API_KEY")
        )
        self.model = model
        self.history: List[ActionRecord] = []
        self.max_steps = 20

    async def connect(self):
        """Connect to the phone."""
        await self.client.connect()
        if await self.client.ping():
            print("Connected to phone")
        else:
            raise ConnectionError("Phone not responding")

    async def disconnect(self):
        """Disconnect from the phone."""
        await self.client.disconnect()

    async def execute_task(self, task: str) -> bool:
        """
        Execute a high-level task using Claude for decision-making.

        This is the cloud-side AI loop:
        1. Get device state from phone
        2. Send state + task to Claude
        3. Claude decides action
        4. Send action to phone
        5. Repeat until done
        """
        print(f"\n{'='*60}")
        print(f"Task: {task}")
        print(f"{'='*60}\n")

        self.history = []
        previous_ui_hash = 0

        for step in range(1, self.max_steps + 1):
            print(f"\n--- Step {step} ---")

            # Get device state (with screenshot after step 1)
            include_screenshot = step > 1
            state = await self.client.get_state(include_screenshot=include_screenshot)

            current_ui_hash = hash(json.dumps(state.ui_tree))
            ui_changed = previous_ui_hash == 0 or current_ui_hash != previous_ui_hash

            print(f"Foreground: {state.foreground_package}")
            print(f"UI changed: {ui_changed}")

            # Build context for Claude
            action = await self._decide_action(task, state, ui_changed)

            if action is None:
                print("ERROR: Claude couldn't decide an action")
                return False

            print(f"Claude says: {action}")

            # Check if done
            if action.lower() == "done":
                print("\n✓ Task complete!")
                return True

            # Execute the action
            result = await self.client.execute_action(action)
            print(f"Result: {'✓' if result.success else '✗'} {result.message}")

            # Record this step
            self.history.append(ActionRecord(
                step=step,
                action=action,
                result="SUCCESS" if result.success else f"FAILED: {result.message}",
                ui_changed=ui_changed
            ))

            previous_ui_hash = current_ui_hash

            # Small delay for UI to settle
            await asyncio.sleep(0.5)

        print(f"\n✗ Reached maximum steps ({self.max_steps})")
        return False

    async def _decide_action(
        self,
        task: str,
        state: DeviceState,
        ui_changed: bool
    ) -> Optional[str]:
        """
        Ask Claude to decide the next action.
        """
        # Build the prompt
        system_prompt = self._build_system_prompt(state, ui_changed)

        # Build messages
        messages = [{"role": "user", "content": self._build_user_content(task, state)}]

        try:
            response = self.anthropic.messages.create(
                model=self.model,
                max_tokens=256,
                system=system_prompt,
                messages=messages
            )

            # Extract action from response
            text = response.content[0].text.strip()

            # Try to parse as JSON
            try:
                data = json.loads(text)
                action_type = data.get("action", "")
                params = data.get("parameters", {})

                if action_type == "done":
                    return "done"
                elif action_type == "launch":
                    return f"launch {params.get('app', '')}"
                elif action_type == "tap":
                    return f"tap {params.get('text', '')}"
                elif action_type == "type":
                    return f"type {params.get('text', '')} into {params.get('field', '')}"
                elif action_type == "scroll_down":
                    return "scroll down"
                elif action_type == "scroll_up":
                    return "scroll up"
                elif action_type == "back":
                    return "back"
                elif action_type == "home":
                    return "home"
                elif action_type == "enter":
                    return "enter"
                else:
                    return text  # Fallback to raw text
            except json.JSONDecodeError:
                return text  # Not JSON, use raw text

        except Exception as e:
            print(f"Claude API error: {e}")
            return None

    def _build_system_prompt(self, state: DeviceState, ui_changed: bool) -> str:
        history_text = ""
        if self.history:
            lines = [f"Step {r.step}: {r.action} → {r.result}" for r in self.history]
            history_text = "\n\nPrevious actions:\n" + "\n".join(lines)

            if ui_changed:
                history_text += "\n[UI_FEEDBACK: Screen changed after last action]"
            else:
                history_text += "\n[UI_FEEDBACK: Screen did NOT change - action may have completed silently]"

        return f"""You are controlling an Android phone remotely. Analyze the UI tree and decide the next action.

Available actions (respond with JSON):
- {{"action": "launch", "parameters": {{"app": "com.package.name"}}}}
- {{"action": "tap", "parameters": {{"text": "Button text"}}}}
- {{"action": "type", "parameters": {{"text": "text to type", "field": "field hint"}}}}
- {{"action": "scroll_down"}}
- {{"action": "scroll_up"}}
- {{"action": "back"}}
- {{"action": "home"}}
- {{"action": "enter"}}
- {{"action": "done"}}  - when task is complete

Rules:
- Do NOT interact with android-vox's own UI
- If in android-vox, launch the app needed for the task
- Do NOT repeat failed actions
- Say "done" only when you have evidence the task succeeded
{history_text}

Respond with ONLY a JSON object, no explanation."""

    def _build_user_content(self, task: str, state: DeviceState) -> list:
        content = []

        # Add screenshot if available
        if state.screenshot:
            content.append({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": state.screenshot
                }
            })

        # Add task and UI tree
        content.append({
            "type": "text",
            "text": f"Task: {task}\n\nUI Tree:\n{json.dumps(state.ui_tree, indent=2)[:5000]}"
        })

        return content


async def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    phone_ip = sys.argv[1]
    task = " ".join(sys.argv[2:])

    controller = CloudController(phone_ip)

    try:
        await controller.connect()
        success = await controller.execute_task(task)
        print(f"\nFinal result: {'Success' if success else 'Failed'}")
        print(f"Total steps: {len(controller.history)}")
    finally:
        await controller.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
