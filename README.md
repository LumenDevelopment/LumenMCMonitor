# LumenMC Monitor

<img src="./images/icon700x700.png" width="100px" height="100px" alt="Icon">

[![Discord](https://badgen.net/discord/members/zUueFq98bB?icon=discord&label=Discord&list=what)](https://discord.gg/zUueFq98bB)
![GitHub Release](https://img.shields.io/github/v/release/LumenDevelopment/LumenMCMonitor?include_prereleases&display_name=release)

## Minecraft plugin that sends Discord Webhooks from your server!
It's designed to work with [LumenMC](https://builtbybit.com/resources/lumenmc.52562/), but it works fine even without it.
Basic and advanced configuration is available through ```config.yml```.
It sends everything from server startup, player chats, commands to death messages.
It even notifies you when the server stops ticking!

> ⚠️ Warning
><br>The plugin is in a beta stage of development. You may experience bugs.

## Features
1. Can send messages:
    - Server start/stop
    - Player's death
    - Gamemode change
    - Join and quit
    - Console messages and errors
    - Watchdog - stopped ticking
    - And more...
2. Message customization
3. Ping prevention
4. Filters
5. Embeds
6. Localization
7. Multi webhook support
8. PlaceholderAPI support
9. Addon support
10. User webhooks

## Commands
- /lumenmc reload - reloads config
- /lumenmc test - sends a test webhook
- /lumenmc lang - configures languages
    - add - creates new language
    - edit - edits existing language
    - remove - removes existing language
    - list - lists available languages
    - set - sets language
- /lumenmc webhook - manages webhooks
    - add - adds webhook
    - remove - removes webhook
- /lumenmc config - edits config
- /lumenmc addon - addon commands
- /webhook - command for creating user webhooks
    - add - adds user webhook
    - remove - removes user webhook
    - list - lists user's webhooks
    - addon - addon commands

## Addons
This plugin supports addons. We have some premade in our [addon repo](https://github.com/LumenDevelopment/LumenMCMonitor-Addons).
[Click to view more](ADDONS.md)

## License
We're using [GNU GENERAL PUBLIC LICENSE v3](LICENSE)

## Contributing
Pull requests are welcome.
ANY feedback appreciated.
If you find issues you can open new issue here or open a ticket at our discord.

## Links
- <a href="https://www.lumenvm.cloud/" target="_blank">Lumen Website</a>
- <a href="https://discord.gg/zUueFq98bB/" target="_blank">Lumen Development Discord</a>
- <a href="https://modrinth.com/plugin/lumenmc-monitor" target="_blank">Modrinth page</a>