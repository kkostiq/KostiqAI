# KostiqAI: The Unpredictable Minecraft AI Director

> KostiqAI is a server-side Minecraft mod for Fabric that introduces a dynamic "AI Director" to your world. Its goal is to create unpredictable, challenging, and hilarious moments by analyzing player behavior and triggering a wide range of "actions" or "pranks."

Whether you want a constantly evolving challenge for experienced players or just want to add some chaos to your server, KostiqAI delivers. It can be powered by a sophisticated OpenAI integration or a robust built-in heuristic planner that provides a balanced experience without any external setup.

---

## ✨ Features

* 🧠 **Dynamic AI Director:** An intelligent planner that observes players and triggers actions to keep the game interesting.
* 🤖 **Optional OpenAI Integration:** Connect to the OpenAI API (e.g., GPT-4o mini) for advanced, context-aware decision-making.
* 🔧 **Robust Heuristic Planner:** A powerful built-in mode that intelligently selects actions based on player state and game difficulty—**no API key required!**
* 📈 **Difficulty Curves:** Choose between three distinct modes:
    * **Linear:** A consistent, medium level of challenge.
    * **Progressive:** Starts easy and gets relentlessly harder over time.
    * **Balanced:** Cycles between periods of calm ("safe") and intense chaos ("nasty").
* ⚙️ **Highly Configurable:** Control everything from the time between events to player-specific difficulty settings and banned actions via in-game commands.
* 💥 **Huge Variety of Actions:** From subtle pranks like shuffling a player's hotbar to major events like spawning a temporary Wither, the mod has a massive arsenal of tricks.

---

## 🛠️ Installation

This is a server-side mod for the Fabric mod loader.

1.  Ensure you have a Fabric server set up for Minecraft 1.21.x.
2.  Download the latest release of the `KostiqAI.jar` file from the project's **Releases** page.
3.  Place the `.jar` file into your server's `/mods` folder.
4.  Restart your server. The mod will generate a default configuration file at `config/kostiqai.json`.
5.  By default, the mod will use its powerful **built-in Heuristic planner**. If you want to use the OpenAI planner, follow the steps in the next section.

---

## 🤖 Configuring the OpenAI Planner (Optional)

You can enable a more advanced AI planner by connecting the mod to your OpenAI account.

**Important:** For security, the API key **cannot** be set with an in-game command. You must set it as an **environment variable** in your server's terminal *before* you launch the server JAR.

### Step 1: Set the Environment Variable

Choose the command for your server's operating system.

#### On Linux or macOS

```bash
# 1. Set the variable in your terminal
export OPENAI_API_KEY="sk-YourSecretApiKeyGoesHere"

# 2. Then, start your server in the SAME terminal window
java -jar fabric-server-launch.jar
```

#### On Windows (Command Prompt - CMD)

```batch
:: 1. Set the variable in your command prompt
set OPENAI_API_KEY=sk-YourSecretApiKeyGoesHere

:: 2. Then, start your server in the SAME window
java -jar fabric-server-launch.jar
```

#### On Windows (PowerShell)

```powershell
# 1. Set the variable in your PowerShell window
$env:OPENAI_API_KEY="sk-YourSecretApiKeyGoesHere"

# 2. Then, start your server in the SAME window
java -jar fabric-server-launch.jar
```

> **Note:** This variable must be set this way every time you restart the server. For a permanent solution, you can add the command to your server's start script or set it globally in your OS.

### Step 2: Enable AI Mode In-Game

Once the server is running with the environment variable set, use the following command in-game:
`/kostiqai config ai true`

The mod will now use the OpenAI planner. You can switch back to the built-in planner at any time with `/kostiqai config ai false`.

---

## ⌨️ Commands

All commands start with `/kostiqai` and require operator permission (level 2 or higher).

### Main Commands

| Command | Description |
| :--- | :--- |
| `/kostiqai status` | Displays a summary of the mod's current status. |
| `/kostiqai toggle <on\|off>` | The master switch to enable or disable the entire mod. |
| `/kostiqai trigger` | Forces the AI Director to immediately plan and execute an action. |
| `/kostiqai help` | Shows a list of the main commands. |

### Configuration Commands

| Command | Parameters | Description |
| :--- | :--- | :--- |
| `/kostiqai config ai` | `<true\|false>` | Toggles between the OpenAI planner and the built-in Heuristic planner. |
| `/kostiqai config difficulty` | `<linear\|progressive\|balanced>` | Sets the global difficulty curve. |
| `/kostiqai config period` | `<seconds>` | Sets the base time between action plans. |
| `/kostiqai config cooldown` | `<seconds>` | Sets the additional cooldown period after an action. |
| `/kostiqai config randomness` | `<0-100>` | Sets the "temperature" for the AI planner (higher is more creative). |
| `/kostiqai config dryrun` | `<true\|false>` | If true, logs actions to console without executing them. |

### Action Management

| Command | Parameters | Description |
| :--- | :--- | :--- |
| `/kostiqai actions ban` | `<type>` | Prevents a specific action type from being used. |
| `/kostiqai actions allow` | `<type>` | Re-enables a banned action type. |
| `/kostiqai actions list` |  | Shows a list of all currently banned actions. |

### Player Management

| Command | Parameters | Description |
| :--- | :--- | :--- |
| `/kostiqai player <name> mode` | `<auto\|mild\|spicy\|off>` | Overrides the global difficulty for a specific player. |

---

## 📜 Full Action List

These are all the possible events the AI Director can trigger, grouped by severity.

### Mild Actions (Severity 1)

| Action Name | Description |
| :--- | :--- |
| `SLOW` | Applies a slowness effect. |
| `FATIGUE` | Applies mining fatigue. |
| `NAUSEA` | Applies the nausea (wobbly screen) effect. |
| `BLIND` | Applies the blindness effect. |
| `LEVITATE` | Briefly makes the player float. |
| `HOTBAR_SHUFFLE` | Randomizes the player's hotbar slots. |
| `SWITCH_WHILE_MINING` | Swaps the player's tool with another item while they are mining. |
| `ITEM_MAGNET` | Sucks all nearby item drops into the player's inventory. |
| `RUBBERBAND` | Snaps the player back to their previous location after a short delay. |
| `INVENTORY_SPAM` | Fills empty inventory slots with junk items like seeds and flowers. |

### Moderate Actions (Severity 2)

| Action Name | Description |
| :--- | :--- |
| `ICE_RING` | Creates a large ring of ice under the player. |
| `BOUNCY_FLOOR` | Turns the block under the player into a slime block. |
| `HONEY_TRAP` | Surrounds the player's feet with honey blocks. |
| `SAND_DRIZZLE` | Drops three layers of sand on top of the player. |
| `FIRE_UNDER` | Sets a fire at the player's feet. |
| `UNEQUIP_ARMOR` | Removes the player's armor and places it in their inventory. |
| `LEVITATE_LONG` | A longer version of the levitation effect. |
| `YEET_EXPLOSION` | A non-damaging explosion that launches the player. |
| `HYPER_SPEED` | Gives the player an extreme, uncontrollable burst of speed. |
| `FORCE_RIDE` | Forces the player to start riding a nearby entity. |
| `FLIP_VIEW` | Periodically flips the player's camera upside down. |

### Spicy Actions (Severity 3)

| Action Name | Description |
| :--- | :--- |
| `CAGE` | Traps the player in a tall glass cage that builds from below their feet. |
| `SPAWN` | Summons a batch of biome-appropriate hostile mobs around the player. |
| `PISTON_SHOVE` | A powerful, invisible force that launches the player a great distance. |
| `DROP_INVENTORY` | Causes the player to drop their entire inventory on the ground (permanently). |
| `FLOOR_PULL` | Deletes the 5 blocks directly beneath the player's feet. |
| `BERSERK` | Gives the player a Strength boost and spawns weak mobs, forcing combat. |

### Extreme Actions (Severity 4-5)

| Action Name | Description |
| :--- | :--- |
| `WITHER_TEMPORARY` | Spawns a Wither boss near the player for 15 seconds. |
| `WITHER_MAYBE` | Has a very small chance to spawn a permanent Wither boss. |
| `LAVA_TRAP` | Replaces the block under the player with a lava source block. |


## ⚠️ Disclaimer

This project is **not affiliated with, endorsed by, or associated with Mojang, Microsoft, or OpenAI** in any way.

KostiqAI is provided **“as is”**, without any warranty of any kind, express or implied.  
The authors and contributors are **not responsible** for any data loss, world corruption, server instability, bans, or other issues that may arise from using this mod.

By installing or running this software, you agree that **you use it at your own risk**.

All Minecraft assets, names, and related media are trademarks of Mojang and Microsoft.
