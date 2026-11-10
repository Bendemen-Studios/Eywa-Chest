# EywaChest

Paper 26.3 plugin foundation for EywaMC. Requires Java 25.

Dependencies: ExcellentEconomy 2.8.0 and ExcellentShop 5.1.7.

Commands:
- `/eywachest give <player> [amount]`
- `/eywachest limit <player> <amount>`
- `/eywachest upgrade <player> <extra>`
- `/eywachest info`
- `/eywachest trust <player>` and `/eywachest untrust <player>` (look at the chest)
- `/eywachest currency <id>` (look at the chest)
- `/eywachest reload`

Permissions: `eywachest.use`, `eywachest.give`, `eywachest.chestadmin`.

Default chest limit is 2; double chests are intended to count as one. Data is stored in `plugins/EywaChest/data.yml`.

Build with `mvn -U clean package`.

**Current limitation:** autoselling is not active yet. The sale adapter must be implemented and verified against the installed ExcellentShop and ExcellentEconomy APIs before this is a production-ready autosell plugin.
